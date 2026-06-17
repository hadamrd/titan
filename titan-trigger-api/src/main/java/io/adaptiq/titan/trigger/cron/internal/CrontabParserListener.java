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

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by {@link CrontabParser}.
 */
public interface CrontabParserListener extends ParseTreeListener {
  /**
   * Enter a parse tree produced by {@link CrontabParser#startRule}.
   *
   * @param ctx the parse tree
   */
  void enterStartRule(CrontabParser.StartRuleContext ctx);

  /**
   * Exit a parse tree produced by {@link CrontabParser#startRule}.
   *
   * @param ctx the parse tree
   */
  void exitStartRule(CrontabParser.StartRuleContext ctx);

  /**
   * Enter a parse tree produced by {@link CrontabParser#expr}.
   *
   * @param ctx the parse tree
   */
  void enterExpr(CrontabParser.ExprContext ctx);

  /**
   * Exit a parse tree produced by {@link CrontabParser#expr}.
   *
   * @param ctx the parse tree
   */
  void exitExpr(CrontabParser.ExprContext ctx);

  /**
   * Enter a parse tree produced by {@link CrontabParser#term}.
   *
   * @param ctx the parse tree
   */
  void enterTerm(CrontabParser.TermContext ctx);

  /**
   * Exit a parse tree produced by {@link CrontabParser#term}.
   *
   * @param ctx the parse tree
   */
  void exitTerm(CrontabParser.TermContext ctx);

  /**
   * Enter a parse tree produced by {@link CrontabParser#token}.
   *
   * @param ctx the parse tree
   */
  void enterToken(CrontabParser.TokenContext ctx);

  /**
   * Exit a parse tree produced by {@link CrontabParser#token}.
   *
   * @param ctx the parse tree
   */
  void exitToken(CrontabParser.TokenContext ctx);
}
