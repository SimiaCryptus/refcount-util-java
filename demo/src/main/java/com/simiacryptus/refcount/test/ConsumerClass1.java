package com.simiacryptus.refcount.test;

import java.util.Arrays;

public class ConsumerClass1 {
  public static void main(String... args) {
    final DataType1 datum1 = new DataType1();
    System.out.println(String.format("Instantiated %s", datum1));
    for (int i = 0; i < 10; i++) {
      doSomething(datum1.addRef());
    }
    final DataType2 datum2 = new DataType2();
    System.out.println(String.format("Instantiated %s", datum2));
    for (int i = 0; i < 10; i++) {
      doSomething(datum2.addRef());
    }
    final DataType3 datum3 = new DataType3(
        com.simiacryptus.refcount.test.DataType1.addRefs(new DataType1[] { datum1 }));
    datum1.freeRef();
    System.out.println(String.format("Instantiated %s", datum2));
    datum2.freeRef();
    for (int i = 0; i < 10; i++) {
      doSomething(datum3.addRef());
    }
    datum3.freeRef();
  }

  private static void doSomething(DataType1 obj) {
    System.out.println(String.format("Increment %s", obj));
    obj.value++;
    obj.freeRef();
  }

  private static void doSomething(DataType2 obj) {
    System.out.println(String.format("Increment %s", obj));
    obj.value.value++;
    obj.freeRef();
  }

  private static void doSomething(DataType3 obj) {
    System.out.println(String.format("Increment %s", obj));
    Arrays.stream(obj.values).forEach(x -> x.value++);
    obj.freeRef();
  }
}
