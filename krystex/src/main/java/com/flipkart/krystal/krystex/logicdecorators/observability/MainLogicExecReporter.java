package com.flipkart.krystal.krystex.logicdecorators.observability;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.flipkart.krystal.data.Errable.empty;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.io.File.pathSeparator;
import static java.io.File.separator;
import static java.util.concurrent.CompletableFuture.allOf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flipkart.krystal.data.Errable;
import com.flipkart.krystal.data.Facets;
import com.flipkart.krystal.data.Results;
import com.flipkart.krystal.krystex.OutputLogic;
import com.flipkart.krystal.krystex.OutputLogicDefinition;
import com.flipkart.krystal.krystex.kryon.KryonId;
import com.flipkart.krystal.krystex.kryon.KryonLogicId;
import com.flipkart.krystal.krystex.logicdecoration.OutputLogicDecorator;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;

@Slf4j
public class MainLogicExecReporter implements OutputLogicDecorator {

  private final KryonExecutionReport kryonExecutionReport;
  private static final String DATE_TIME_PATTERN = "yyyy-MM-dd_HH:mm:ss";
  private static final String FILE_PATH =
      separator + "tmp" + separator + "krystal_exec_graph_";
  private final ObjectMapper objectMapper;

  public MainLogicExecReporter(KryonExecutionReport kryonExecutionReport) {
    this.kryonExecutionReport = kryonExecutionReport;
    objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .setSerializationInclusion(NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);
  }

  @Override
  public OutputLogic<Object> decorateLogic(
      OutputLogic<Object> logicToDecorate, OutputLogicDefinition<Object> originalLogicDefinition) {
    return inputs -> {
      KryonId kryonId = originalLogicDefinition.kryonLogicId().kryonId();
      KryonLogicId kryonLogicId = originalLogicDefinition.kryonLogicId();
      /*
       Report logic start
      */
      kryonExecutionReport.reportMainLogicStart(kryonId, kryonLogicId, inputs);

      /*
       Execute logic
      */
      ImmutableMap<Facets, CompletableFuture<@Nullable Object>> results =
          logicToDecorate.execute(inputs);
      /*
       Report logic end
      */
      allOf(results.values().toArray(CompletableFuture[]::new))
          .whenComplete(
              (unused, throwable) -> {
                kryonExecutionReport.reportMainLogicEnd(
                    kryonId,
                    kryonLogicId,
                    new Results<>(
                        results.entrySet().stream()
                            .collect(
                                toImmutableMap(
                                    Entry::getKey,
                                    e ->
                                        e.getValue()
                                            .handle(Errable::errableFrom)
                                            .getNow(empty())))));
              });
      return results;
    };
  }

  @Override
  public String getId() {
    return MainLogicExecReporter.class.getName();
  }

  public KryonExecutionReport getKryonExecutionReport() {
    return this.kryonExecutionReport;
  }

  @Override
  public void onComplete() {
    String htmlString = generateGraph();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    String fileName = LocalDateTime.now().format(formatter) + ".html";
    writeToFile(htmlString, FILE_PATH + fileName);
  }

  public static void writeToFile(String content, String filePath) {
    try {
      Path path = Paths.get(filePath);
      if (path.getParent() == null) {
        log.error(
            "Parent path is null so not storing the html output of DefaultKryonExecutionReport");
        return;
      }
      if (!Files.exists(path.getParent())) {
        Files.createDirectories(path.getParent());
      }

      FileWriter writer = new FileWriter(filePath, StandardCharsets.UTF_8);
      writer.write(content);
      writer.close();
    } catch (IOException e) {
      log.error("Error writing file: {} with path: {}" + e.getMessage(), filePath);
    }
  }

  private String generateGraph() {
    try {
      String jsonString = objectMapper.writeValueAsString(kryonExecutionReport);
      return GenerateHtml.generateHtml(jsonString);
    } catch (JsonProcessingException e) {
      log.error("Error came while serializing kryonExecutionReport");
    }
    return "";
  }
}
