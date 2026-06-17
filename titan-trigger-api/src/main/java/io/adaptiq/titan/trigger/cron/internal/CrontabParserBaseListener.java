/*
 * Adapted from an MIT-licensed cron-scheduling implementation.
 *
 * Modifications vs upstream:
 *  - Repackaged to io.adaptiq.titan.trigger.cron.internal.
 *
 * The grammar is otherwise carried verbatim to preserve H-hashing, @daily/@hourly,
 * and TZ= behaviour for users' existing cron strings.
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
// Generated from the crontab grammar by ANTLR 4.13.2
package io.adaptiq.titan.trigger.cron.internal;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * This class provides an empty implementation of {@link CrontabParserListener}, which can be
 * extended to create a listener which only needs to handle a subset of the available methods.
 */
@SuppressWarnings("CheckReturnValue")
public class CrontabParserBaseListener implements CrontabParserListener {
  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void enterStartRule(CrontabParser.StartRuleContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void exitStartRule(CrontabParser.StartRuleContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void enterExpr(CrontabParser.ExprContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void exitExpr(CrontabParser.ExprContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void enterTerm(CrontabParser.TermContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void exitTerm(CrontabParser.TermContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void enterToken(CrontabParser.TokenContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void exitToken(CrontabParser.TokenContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void enterEveryRule(ParserRuleContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void exitEveryRule(ParserRuleContext ctx) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void visitTerminal(TerminalNode node) {}

  /**
   * {@inheritDoc}
   *
   * <p>The default implementation does nothing.
   */
  @Override
  public void visitErrorNode(ErrorNode node) {}
}
