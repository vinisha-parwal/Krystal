package com.flipkart.krystal.caramel.samples.basic;

import com.flipkart.krystal.caramel.model.Field;
import com.flipkart.krystal.caramel.model.SimpleField;
import com.flipkart.krystal.caramel.model.Value;
import com.flipkart.krystal.caramel.model.ValueImpl;

// AutoGenerated and managed by Caramel
final class TransformedProductWfPayload implements TransformedProductWfPayloadDefinition {
  public interface TransformedProductWfFields {
    Field<ProductUpdateEvent, TransformedProductWfPayload> productUpdateEvent =
        new SimpleField<>(
            "productUpdateEvent",
            TransformedProductWfPayload.class,
            TransformedProductWfPayload::productUpdateEvent,
            TransformedProductWfPayload::setProductUpdateEvent);

    Field<TransformedProduct, TransformedProductWfPayload> transformedProduct =
        new SimpleField<>(
            "transformedProduct",
            TransformedProductWfPayload.class,
            TransformedProductWfPayload::transformedProduct,
            TransformedProductWfPayload::setTransformedProduct);
  }

  private final Value<ProductUpdateEvent, TransformedProductWfPayload> productUpdateEvent =
      new ValueImpl<>(TransformedProductWfFields.productUpdateEvent, this);

  private final Value<TransformedProduct, TransformedProductWfPayload> transformedProduct =
      new ValueImpl<>(TransformedProductWfFields.transformedProduct, this);

  @Override
  public ProductUpdateEvent productUpdateEvent() {
    return productUpdateEvent.getOrThrow();
  }

  public void setProductUpdateEvent(ProductUpdateEvent productUpdateEvent) {
    this.productUpdateEvent.set(productUpdateEvent);
  }

  @Override
  public TransformedProduct transformedProduct() {
    return transformedProduct.getOrThrow();
  }

  public void setTransformedProduct(TransformedProduct transformedProduct) {
    this.transformedProduct.set(transformedProduct);
  }
}