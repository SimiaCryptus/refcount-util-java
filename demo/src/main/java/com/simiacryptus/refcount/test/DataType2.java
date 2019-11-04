package com.simiacryptus.refcount.test;

import com.simiacryptus.lang.ref.ReferenceCountingBase;

public class DataType2 extends ReferenceCountingBase {
  public DataType1 value;

  public DataType2() {
    this(new DataType1());
  }

  public DataType2(DataType1 value) {
    this.value = value;
    value.freeRef();
  }

  @Override
  public String toString() {
    return "DataType2{" + "values=" + value + '}';
  }

  public @Override void _free() {
    value.freeRef();
  }

  public @Override DataType2 addRef() {
    return (DataType2) super.addRef();
  }

  public static DataType2[] addRefs(DataType2[] array) {
    return java.util.Arrays.stream(array).filter((x) -> x == null).map(DataType2::addRef)
        .toArray((x) -> new DataType2[x]);
  }

  public static void freeRefs(DataType2[] array) {
    java.util.Arrays.stream(array).filter((x) -> x == null).forEach(DataType2::freeRef);
  }
}
