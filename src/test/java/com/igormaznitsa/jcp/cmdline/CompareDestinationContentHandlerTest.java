/*
 * Copyright 2015 Igor Maznitsa.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.igormaznitsa.jcp.cmdline;

import com.igormaznitsa.jcp.context.PreprocessorContext;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class CompareDestinationContentHandlerTest extends AbstractCommandLineHandlerTest {
  private static final CompareDestinationContentHandler HANDLER = new CompareDestinationContentHandler();

  @Override
  public void testThatTheHandlerInTheHandlerList () {
    assertHandlerInTheHandlerList(HANDLER);
  }

  @Override
  public void testExecution () throws Exception {
    final PreprocessorContext mock = mock(PreprocessorContext.class);

    assertFalse(HANDLER.processCommandLineKey("/z:", mock));
    assertFalse(HANDLER.processCommandLineKey("/ZZ", mock));
    assertFalse(HANDLER.processCommandLineKey("/Z ", mock));
    verify(mock, never()).setCompareDestination(anyBoolean());

    assertTrue(HANDLER.processCommandLineKey("/Z", mock));
    verify(mock).setCompareDestination(true);
    reset(mock);

    assertTrue(HANDLER.processCommandLineKey("/z", mock));
    verify(mock).setCompareDestination(true);
    reset(mock);
  }

  @Override
  public void testName () {
    assertEquals("/Z", HANDLER.getKeyName());
  }

  @Override
  public void testDescription () {
    assertDescription(HANDLER);
  }
}
