package org.codice.ddf.test.common.annotations;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExamResultLogger extends TestWatcher {
  protected static final Logger LOGGER = LoggerFactory.getLogger(ExamResultLogger.class);

  @Override
  protected void failed(Throwable e, Description description) {
    LOGGER.info("FAILURE: {} failed.", description.getMethodName());
  }

  @Override
  protected void succeeded(Description description) {
    LOGGER.info("SUCCESS: {} passed.", description.getMethodName());
  }
}
