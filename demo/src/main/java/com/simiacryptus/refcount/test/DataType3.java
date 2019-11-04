package com.simiacryptus.refcount.test;

import com.simiacryptus.lang.ref.ReferenceCountingBase;

public class DataType3 extends ReferenceCountingBase {
  public DataType1[] values;

  public DataType3(DataType1... values) {
    this.values = values;
    com.simiacryptus.refcount.test.DataType1.freeRefs(values);
  }

  @Override
  public String toString() {
    return "DataType2{" + "values=" + values + '}';
  }

  public @Override void _free() {
  }

  public @Override DataType3 addRef() {
    return (DataType3) super.addRef();
  }

  public static DataType3[] addRefs(DataType3[] array) {
    return java.util.Arrays.stream(array).filter((x) -> x == null).map(DataType3::addRef)
        .toArray((x) -> new DataType3[x]);
  }

  public static void freeRefs(DataType3[] array) {
    java.util.Arrays.stream(array).filter((x) -> x == null).forEach(DataType3::freeRef);
  }
}
