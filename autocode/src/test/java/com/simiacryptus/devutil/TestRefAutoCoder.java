package com.simiacryptus.devutil;

import org.junit.Test;

public class TestRefAutoCoder {
  @Test
  public void test() {
    new RefAutoCoder("../demo").setAddRefcounting(true).apply();
  }
  @Test
  public void remove() {
    new RefAutoCoder("../demo").setAddRefcounting(false).apply();
  }
}
