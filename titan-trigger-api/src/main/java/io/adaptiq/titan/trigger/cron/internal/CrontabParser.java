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

import java.util.List;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;

@SuppressWarnings({
  "all",
  "warnings",
  "unchecked",
  "unused",
  "cast",
  "CheckReturnValue",
  "this-escape"
})
public class CrontabParser extends BaseParser {
  static {
    RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION);
  }

  protected static final DFA[] _decisionToDFA;
  protected static final PredictionContextCache _sharedContextCache = new PredictionContextCache();
  public static final int TOKEN = 1,
      WS = 2,
      MINUS = 3,
      STAR = 4,
      DIV = 5,
      OR = 6,
      AT = 7,
      H = 8,
      LPAREN = 9,
      RPAREN = 10,
      YEARLY = 11,
      ANNUALLY = 12,
      MONTHLY = 13,
      WEEKLY = 14,
      DAILY = 15,
      MIDNIGHT = 16,
      HOURLY = 17;
  public static final int RULE_startRule = 0, RULE_expr = 1, RULE_term = 2, RULE_token = 3;

  private static String[] makeRuleNames() {
    return new String[] {"startRule", "expr", "term", "token"};
  }

  public static final String[] ruleNames = makeRuleNames();

  private static String[] makeLiteralNames() {
    return new String[] {
      null,
      null,
      null,
      "'-'",
      "'*'",
      "'/'",
      "','",
      "'@'",
      "'H'",
      "'('",
      "')'",
      "'yearly'",
      "'annually'",
      "'monthly'",
      "'weekly'",
      "'daily'",
      "'midnight'",
      "'hourly'"
    };
  }

  private static final String[] _LITERAL_NAMES = makeLiteralNames();

  private static String[] makeSymbolicNames() {
    return new String[] {
      null,
      "TOKEN",
      "WS",
      "MINUS",
      "STAR",
      "DIV",
      "OR",
      "AT",
      "H",
      "LPAREN",
      "RPAREN",
      "YEARLY",
      "ANNUALLY",
      "MONTHLY",
      "WEEKLY",
      "DAILY",
      "MIDNIGHT",
      "HOURLY"
    };
  }

  private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
  public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

  /**
   * @deprecated Use {@link #VOCABULARY} instead.
   */
  @Deprecated public static final String[] tokenNames;

  static {
    tokenNames = new String[_SYMBOLIC_NAMES.length];
    for (int i = 0; i < tokenNames.length; i++) {
      tokenNames[i] = VOCABULARY.getLiteralName(i);
      if (tokenNames[i] == null) {
        tokenNames[i] = VOCABULARY.getSymbolicName(i);
      }

      if (tokenNames[i] == null) {
        tokenNames[i] = "<INVALID>";
      }
    }
  }

  @Override
  @Deprecated
  public String[] getTokenNames() {
    return tokenNames;
  }

  @Override
  public Vocabulary getVocabulary() {
    return VOCABULARY;
  }

  @Override
  public String getGrammarFileName() {
    return "CrontabParser.g4";
  }

  @Override
  public String[] getRuleNames() {
    return ruleNames;
  }

  @Override
  public String getSerializedATN() {
    return _serializedATN;
  }

  @Override
  public ATN getATN() {
    return _ATN;
  }

  public CrontabParser(TokenStream input) {
    super(input);
    _interp = new ParserATNSimulator(this, _ATN, _decisionToDFA, _sharedContextCache);
  }

  @SuppressWarnings("CheckReturnValue")
  public static class StartRuleContext extends ParserRuleContext {
    public CronTab table;
    public ExprContext expr;

    public List<ExprContext> expr() {
      return getRuleContexts(ExprContext.class);
    }

    public ExprContext expr(int i) {
      return getRuleContext(ExprContext.class, i);
    }

    public List<TerminalNode> WS() {
      return getTokens(CrontabParser.WS);
    }

    public TerminalNode WS(int i) {
      return getToken(CrontabParser.WS, i);
    }

    public TerminalNode EOF() {
      return getToken(CrontabParser.EOF, 0);
    }

    public TerminalNode AT() {
      return getToken(CrontabParser.AT, 0);
    }

    public TerminalNode YEARLY() {
      return getToken(CrontabParser.YEARLY, 0);
    }

    public TerminalNode ANNUALLY() {
      return getToken(CrontabParser.ANNUALLY, 0);
    }

    public TerminalNode MONTHLY() {
      return getToken(CrontabParser.MONTHLY, 0);
    }

    public TerminalNode WEEKLY() {
      return getToken(CrontabParser.WEEKLY, 0);
    }

    public TerminalNode DAILY() {
      return getToken(CrontabParser.DAILY, 0);
    }

    public TerminalNode MIDNIGHT() {
      return getToken(CrontabParser.MIDNIGHT, 0);
    }

    public TerminalNode HOURLY() {
      return getToken(CrontabParser.HOURLY, 0);
    }

    public StartRuleContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }

    public StartRuleContext(ParserRuleContext parent, int invokingState, CronTab table) {
      super(parent, invokingState);
      this.table = table;
    }

    @Override
    public int getRuleIndex() {
      return RULE_startRule;
    }

    @Override
    public void enterRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).enterStartRule(this);
    }

    @Override
    public void exitRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).exitStartRule(this);
    }
  }

  public final StartRuleContext startRule(CronTab table) throws RecognitionException {
    StartRuleContext _localctx = new StartRuleContext(_ctx, getState(), table);
    enterRule(_localctx, 0, RULE_startRule);
    try {
      setState(41);
      _errHandler.sync(this);
      switch (_input.LA(1)) {
        case TOKEN:
        case STAR:
        case H:
          enterOuterAlt(_localctx, 1);
          {
            setState(8);
            ((StartRuleContext) _localctx).expr = expr(0);
            _localctx.table.bits[0] = ((StartRuleContext) _localctx).expr.bits;
            setState(10);
            match(WS);
            setState(11);
            ((StartRuleContext) _localctx).expr = expr(1);
            _localctx.table.bits[1] = ((StartRuleContext) _localctx).expr.bits;
            setState(13);
            match(WS);
            setState(14);
            ((StartRuleContext) _localctx).expr = expr(2);
            _localctx.table.bits[2] = ((StartRuleContext) _localctx).expr.bits;
            setState(16);
            match(WS);
            setState(17);
            ((StartRuleContext) _localctx).expr = expr(3);
            _localctx.table.bits[3] = ((StartRuleContext) _localctx).expr.bits;
            setState(19);
            match(WS);
            setState(20);
            ((StartRuleContext) _localctx).expr = expr(4);
            _localctx.table.dayOfWeek = (int) ((StartRuleContext) _localctx).expr.bits;
            setState(22);
            match(EOF);
          }
          break;
        case AT:
          enterOuterAlt(_localctx, 2);
          {
            {
              setState(24);
              match(AT);
              setState(39);
              _errHandler.sync(this);
              switch (_input.LA(1)) {
                case YEARLY:
                  {
                    setState(25);
                    match(YEARLY);

                    _localctx.table.set("H H H H *", getHashForTokens());
                  }
                  break;
                case ANNUALLY:
                  {
                    setState(27);
                    match(ANNUALLY);

                    _localctx.table.set("H H H H *", getHashForTokens());
                  }
                  break;
                case MONTHLY:
                  {
                    setState(29);
                    match(MONTHLY);

                    _localctx.table.set("H H H * *", getHashForTokens());
                  }
                  break;
                case WEEKLY:
                  {
                    setState(31);
                    match(WEEKLY);

                    _localctx.table.set("H H * * H", getHashForTokens());
                  }
                  break;
                case DAILY:
                  {
                    setState(33);
                    match(DAILY);

                    _localctx.table.set("H H * * *", getHashForTokens());
                  }
                  break;
                case MIDNIGHT:
                  {
                    setState(35);
                    match(MIDNIGHT);

                    _localctx.table.set("H H(0-2) * * *", getHashForTokens());
                  }
                  break;
                case HOURLY:
                  {
                    setState(37);
                    match(HOURLY);

                    _localctx.table.set("H * * * *", getHashForTokens());
                  }
                  break;
                default:
                  throw new NoViableAltException(this);
              }
            }
          }
          break;
        default:
          throw new NoViableAltException(this);
      }
    } catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    } finally {
      exitRule();
    }
    return _localctx;
  }

  @SuppressWarnings("CheckReturnValue")
  public static class ExprContext extends ParserRuleContext {
    public int field;
    public long bits = 0;
    public long lhs;
    public long rhs = 0;
    public TermContext term;
    public ExprContext expr;

    public TermContext term() {
      return getRuleContext(TermContext.class, 0);
    }

    public TerminalNode OR() {
      return getToken(CrontabParser.OR, 0);
    }

    public ExprContext expr() {
      return getRuleContext(ExprContext.class, 0);
    }

    public ExprContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }

    public ExprContext(ParserRuleContext parent, int invokingState, int field) {
      super(parent, invokingState);
      this.field = field;
    }

    @Override
    public int getRuleIndex() {
      return RULE_expr;
    }

    @Override
    public void enterRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).enterExpr(this);
    }

    @Override
    public void exitRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).exitExpr(this);
    }
  }

  public final ExprContext expr(int field) throws RecognitionException {
    ExprContext _localctx = new ExprContext(_ctx, getState(), field);
    enterRule(_localctx, 2, RULE_expr);
    int _la;
    try {
      enterOuterAlt(_localctx, 1);
      {
        setState(43);
        ((ExprContext) _localctx).term = term(field);
        ((ExprContext) _localctx).lhs = ((ExprContext) _localctx).term.bits;
        setState(49);
        _errHandler.sync(this);
        _la = _input.LA(1);
        if (_la == OR) {
          {
            setState(45);
            match(OR);
            setState(46);
            ((ExprContext) _localctx).expr = expr(field);
            ((ExprContext) _localctx).rhs = ((ExprContext) _localctx).expr.bits;
          }
        }

        ((ExprContext) _localctx).bits = _localctx.lhs | _localctx.rhs;
      }
    } catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    } finally {
      exitRule();
    }
    return _localctx;
  }

  @SuppressWarnings("CheckReturnValue")
  public static class TermContext extends ParserRuleContext {
    public int field;
    public long bits = 0;
    public int d = NO_STEP;
    public int s;
    public int e;
    public TokenContext token;

    public List<TokenContext> token() {
      return getRuleContexts(TokenContext.class);
    }

    public TokenContext token(int i) {
      return getRuleContext(TokenContext.class, i);
    }

    public TerminalNode MINUS() {
      return getToken(CrontabParser.MINUS, 0);
    }

    public TerminalNode DIV() {
      return getToken(CrontabParser.DIV, 0);
    }

    public TerminalNode STAR() {
      return getToken(CrontabParser.STAR, 0);
    }

    public TerminalNode H() {
      return getToken(CrontabParser.H, 0);
    }

    public TerminalNode LPAREN() {
      return getToken(CrontabParser.LPAREN, 0);
    }

    public TerminalNode RPAREN() {
      return getToken(CrontabParser.RPAREN, 0);
    }

    public TermContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }

    public TermContext(ParserRuleContext parent, int invokingState, int field) {
      super(parent, invokingState);
      this.field = field;
    }

    @Override
    public int getRuleIndex() {
      return RULE_term;
    }

    @Override
    public void enterRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).enterTerm(this);
    }

    @Override
    public void exitRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).exitTerm(this);
    }
  }

  public final TermContext term(int field) throws RecognitionException {
    TermContext _localctx = new TermContext(_ctx, getState(), field);
    enterRule(_localctx, 4, RULE_term);
    int _la;
    try {
      setState(101);
      _errHandler.sync(this);
      switch (getInterpreter().adaptivePredict(_input, 7, _ctx)) {
        case 1:
          enterOuterAlt(_localctx, 1);
          {
            setState(53);
            ((TermContext) _localctx).token = token();
            ((TermContext) _localctx).s = ((TermContext) _localctx).token.value;
            setState(55);
            match(MINUS);
            setState(56);
            ((TermContext) _localctx).token = token();
            ((TermContext) _localctx).e = ((TermContext) _localctx).token.value;
            setState(62);
            _errHandler.sync(this);
            _la = _input.LA(1);
            if (_la == DIV) {
              {
                setState(58);
                match(DIV);
                setState(59);
                ((TermContext) _localctx).token = token();
                ((TermContext) _localctx).d = ((TermContext) _localctx).token.value;
              }
            }

            ((TermContext) _localctx).bits =
                doRange(_localctx.s, _localctx.e, _localctx.d, _localctx.field);
          }
          break;
        case 2:
          enterOuterAlt(_localctx, 2);
          {
            setState(66);
            ((TermContext) _localctx).token = token();

            rangeCheck(((TermContext) _localctx).token.value, _localctx.field);
            ((TermContext) _localctx).bits = 1L << ((TermContext) _localctx).token.value;
          }
          break;
        case 3:
          enterOuterAlt(_localctx, 3);
          {
            setState(69);
            match(STAR);
            setState(74);
            _errHandler.sync(this);
            _la = _input.LA(1);
            if (_la == DIV) {
              {
                setState(70);
                match(DIV);
                setState(71);
                ((TermContext) _localctx).token = token();
                ((TermContext) _localctx).d = ((TermContext) _localctx).token.value;
              }
            }

            ((TermContext) _localctx).bits = doRange(_localctx.d, _localctx.field);
          }
          break;
        case 4:
          enterOuterAlt(_localctx, 4);
          {
            setState(77);
            match(H);
            setState(78);
            match(LPAREN);
            setState(79);
            ((TermContext) _localctx).token = token();
            ((TermContext) _localctx).s = ((TermContext) _localctx).token.value;
            setState(81);
            match(MINUS);
            setState(82);
            ((TermContext) _localctx).token = token();
            ((TermContext) _localctx).e = ((TermContext) _localctx).token.value;
            setState(84);
            match(RPAREN);
            setState(89);
            _errHandler.sync(this);
            _la = _input.LA(1);
            if (_la == DIV) {
              {
                setState(85);
                match(DIV);
                setState(86);
                ((TermContext) _localctx).token = token();
                ((TermContext) _localctx).d = ((TermContext) _localctx).token.value;
              }
            }

            ((TermContext) _localctx).bits =
                doHash(_localctx.s, _localctx.e, _localctx.d, _localctx.field);
          }
          break;
        case 5:
          enterOuterAlt(_localctx, 5);
          {
            setState(93);
            match(H);
            setState(98);
            _errHandler.sync(this);
            _la = _input.LA(1);
            if (_la == DIV) {
              {
                setState(94);
                match(DIV);
                setState(95);
                ((TermContext) _localctx).token = token();
                ((TermContext) _localctx).d = ((TermContext) _localctx).token.value;
              }
            }

            ((TermContext) _localctx).bits = doHash(_localctx.d, _localctx.field);
          }
          break;
      }
    } catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    } finally {
      exitRule();
    }
    return _localctx;
  }

  @SuppressWarnings("CheckReturnValue")
  public static class TokenContext extends ParserRuleContext {
    public int value = 0;
    public Token TOKEN;

    public TerminalNode TOKEN() {
      return getToken(CrontabParser.TOKEN, 0);
    }

    public TokenContext(ParserRuleContext parent, int invokingState) {
      super(parent, invokingState);
    }

    @Override
    public int getRuleIndex() {
      return RULE_token;
    }

    @Override
    public void enterRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).enterToken(this);
    }

    @Override
    public void exitRule(ParseTreeListener listener) {
      if (listener instanceof CrontabParserListener)
        ((CrontabParserListener) listener).exitToken(this);
    }
  }

  public final TokenContext token() throws RecognitionException {
    TokenContext _localctx = new TokenContext(_ctx, getState());
    enterRule(_localctx, 6, RULE_token);
    try {
      enterOuterAlt(_localctx, 1);
      {
        setState(103);
        ((TokenContext) _localctx).TOKEN = match(TOKEN);

        ((TokenContext) _localctx).value =
            Integer.parseInt(((TokenContext) _localctx).TOKEN.getText());
      }
    } catch (RecognitionException re) {
      _localctx.exception = re;
      _errHandler.reportError(this, re);
      _errHandler.recover(this, re);
    } finally {
      exitRule();
    }
    return _localctx;
  }

  public static final String _serializedATN =
      "\u0004\u0001\u0011k\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"
          + "\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0001\u0000\u0001\u0000\u0001"
          + "\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001"
          + "\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001"
          + "\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001"
          + "\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001"
          + "\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0003\u0000(\b"
          + "\u0000\u0003\u0000*\b\u0000\u0001\u0001\u0001\u0001\u0001\u0001\u0001"
          + "\u0001\u0001\u0001\u0001\u0001\u0003\u00012\b\u0001\u0001\u0001\u0001"
          + "\u0001\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002?\b\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002K\b\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"
          + "\u0002\u0003\u0002Z\b\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0001"
          + "\u0002\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002c\b\u0002\u0001"
          + "\u0002\u0003\u0002f\b\u0002\u0001\u0003\u0001\u0003\u0001\u0003\u0001"
          + "\u0003\u0000\u0000\u0004\u0000\u0002\u0004\u0006\u0000\u0000v\u0000)\u0001"
          + "\u0000\u0000\u0000\u0002+\u0001\u0000\u0000\u0000\u0004e\u0001\u0000\u0000"
          + "\u0000\u0006g\u0001\u0000\u0000\u0000\b\t\u0003\u0002\u0001\u0000\t\n"
          + "\u0006\u0000\uffff\uffff\u0000\n\u000b\u0005\u0002\u0000\u0000\u000b\f"
          + "\u0003\u0002\u0001\u0000\f\r\u0006\u0000\uffff\uffff\u0000\r\u000e\u0005"
          + "\u0002\u0000\u0000\u000e\u000f\u0003\u0002\u0001\u0000\u000f\u0010\u0006"
          + "\u0000\uffff\uffff\u0000\u0010\u0011\u0005\u0002\u0000\u0000\u0011\u0012"
          + "\u0003\u0002\u0001\u0000\u0012\u0013\u0006\u0000\uffff\uffff\u0000\u0013"
          + "\u0014\u0005\u0002\u0000\u0000\u0014\u0015\u0003\u0002\u0001\u0000\u0015"
          + "\u0016\u0006\u0000\uffff\uffff\u0000\u0016\u0017\u0005\u0000\u0000\u0001"
          + "\u0017*\u0001\u0000\u0000\u0000\u0018\'\u0005\u0007\u0000\u0000\u0019"
          + "\u001a\u0005\u000b\u0000\u0000\u001a(\u0006\u0000\uffff\uffff\u0000\u001b"
          + "\u001c\u0005\f\u0000\u0000\u001c(\u0006\u0000\uffff\uffff\u0000\u001d"
          + "\u001e\u0005\r\u0000\u0000\u001e(\u0006\u0000\uffff\uffff\u0000\u001f"
          + " \u0005\u000e\u0000\u0000 (\u0006\u0000\uffff\uffff\u0000!\"\u0005\u000f"
          + "\u0000\u0000\"(\u0006\u0000\uffff\uffff\u0000#$\u0005\u0010\u0000\u0000"
          + "$(\u0006\u0000\uffff\uffff\u0000%&\u0005\u0011\u0000\u0000&(\u0006\u0000"
          + "\uffff\uffff\u0000\'\u0019\u0001\u0000\u0000\u0000\'\u001b\u0001\u0000"
          + "\u0000\u0000\'\u001d\u0001\u0000\u0000\u0000\'\u001f\u0001\u0000\u0000"
          + "\u0000\'!\u0001\u0000\u0000\u0000\'#\u0001\u0000\u0000\u0000\'%\u0001"
          + "\u0000\u0000\u0000(*\u0001\u0000\u0000\u0000)\b\u0001\u0000\u0000\u0000"
          + ")\u0018\u0001\u0000\u0000\u0000*\u0001\u0001\u0000\u0000\u0000+,\u0003"
          + "\u0004\u0002\u0000,1\u0006\u0001\uffff\uffff\u0000-.\u0005\u0006\u0000"
          + "\u0000./\u0003\u0002\u0001\u0000/0\u0006\u0001\uffff\uffff\u000002\u0001"
          + "\u0000\u0000\u00001-\u0001\u0000\u0000\u000012\u0001\u0000\u0000\u0000"
          + "23\u0001\u0000\u0000\u000034\u0006\u0001\uffff\uffff\u00004\u0003\u0001"
          + "\u0000\u0000\u000056\u0003\u0006\u0003\u000067\u0006\u0002\uffff\uffff"
          + "\u000078\u0005\u0003\u0000\u000089\u0003\u0006\u0003\u00009>\u0006\u0002"
          + "\uffff\uffff\u0000:;\u0005\u0005\u0000\u0000;<\u0003\u0006\u0003\u0000"
          + "<=\u0006\u0002\uffff\uffff\u0000=?\u0001\u0000\u0000\u0000>:\u0001\u0000"
          + "\u0000\u0000>?\u0001\u0000\u0000\u0000?@\u0001\u0000\u0000\u0000@A\u0006"
          + "\u0002\uffff\uffff\u0000Af\u0001\u0000\u0000\u0000BC\u0003\u0006\u0003"
          + "\u0000CD\u0006\u0002\uffff\uffff\u0000Df\u0001\u0000\u0000\u0000EJ\u0005"
          + "\u0004\u0000\u0000FG\u0005\u0005\u0000\u0000GH\u0003\u0006\u0003\u0000"
          + "HI\u0006\u0002\uffff\uffff\u0000IK\u0001\u0000\u0000\u0000JF\u0001\u0000"
          + "\u0000\u0000JK\u0001\u0000\u0000\u0000KL\u0001\u0000\u0000\u0000Lf\u0006"
          + "\u0002\uffff\uffff\u0000MN\u0005\b\u0000\u0000NO\u0005\t\u0000\u0000O"
          + "P\u0003\u0006\u0003\u0000PQ\u0006\u0002\uffff\uffff\u0000QR\u0005\u0003"
          + "\u0000\u0000RS\u0003\u0006\u0003\u0000ST\u0006\u0002\uffff\uffff\u0000"
          + "TY\u0005\n\u0000\u0000UV\u0005\u0005\u0000\u0000VW\u0003\u0006\u0003\u0000"
          + "WX\u0006\u0002\uffff\uffff\u0000XZ\u0001\u0000\u0000\u0000YU\u0001\u0000"
          + "\u0000\u0000YZ\u0001\u0000\u0000\u0000Z[\u0001\u0000\u0000\u0000[\\\u0006"
          + "\u0002\uffff\uffff\u0000\\f\u0001\u0000\u0000\u0000]b\u0005\b\u0000\u0000"
          + "^_\u0005\u0005\u0000\u0000_`\u0003\u0006\u0003\u0000`a\u0006\u0002\uffff"
          + "\uffff\u0000ac\u0001\u0000\u0000\u0000b^\u0001\u0000\u0000\u0000bc\u0001"
          + "\u0000\u0000\u0000cd\u0001\u0000\u0000\u0000df\u0006\u0002\uffff\uffff"
          + "\u0000e5\u0001\u0000\u0000\u0000eB\u0001\u0000\u0000\u0000eE\u0001\u0000"
          + "\u0000\u0000eM\u0001\u0000\u0000\u0000e]\u0001\u0000\u0000\u0000f\u0005"
          + "\u0001\u0000\u0000\u0000gh\u0005\u0001\u0000\u0000hi\u0006\u0003\uffff"
          + "\uffff\u0000i\u0007\u0001\u0000\u0000\u0000\b\')1>JYbe";
  public static final ATN _ATN = new ATNDeserializer().deserialize(_serializedATN.toCharArray());

  static {
    _decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
    for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
      _decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
    }
  }
}
