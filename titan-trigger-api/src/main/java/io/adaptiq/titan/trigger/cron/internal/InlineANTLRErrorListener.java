/*
 * Adapted from an MIT-licensed cron-scheduling implementation — an in-package
 * replacement for the upstream ANTLR error listener.
 *
 * Modifications vs upstream:
 *  - Throws IllegalArgumentException instead of the legacy ANTLR v2 exception type
 *    (that legacy dependency is not on this classpath).
 *  - Dropped the upstream access-restriction annotation — that library is not on
 *    the classpath.
 *
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, InfraDNA, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.adaptiq.titan.trigger.cron.internal;

import java.util.function.Supplier;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/** ANTLR4 error listener that throws {@link IllegalArgumentException} on syntax error. */
final class InlineANTLRErrorListener extends BaseErrorListener {

  private final Supplier<String> errorMessageSupplier;

  InlineANTLRErrorListener() {
    this.errorMessageSupplier = () -> null;
  }

  InlineANTLRErrorListener(Supplier<String> errorMessageSupplier) {
    this.errorMessageSupplier = errorMessageSupplier;
  }

  @Override
  public void syntaxError(
      Recognizer<?, ?> recognizer,
      Object offendingSymbol,
      int line,
      int charPositionInLine,
      String msg,
      RecognitionException e) {
    String overridden = errorMessageSupplier.get();
    if (overridden != null) {
      msg = overridden;
    }
    throw new IllegalArgumentException(formatMessage(line, charPositionInLine, msg), e);
  }

  private static String formatMessage(int line, int column, String message) {
    StringBuilder sb = new StringBuilder();
    if (line != -1) {
      sb.append("line ");
      sb.append(line);
      if (column != -1) {
        sb.append(":");
        sb.append(column);
      }
      sb.append(": ");
    }
    sb.append(message);
    return sb.toString();
  }
}
