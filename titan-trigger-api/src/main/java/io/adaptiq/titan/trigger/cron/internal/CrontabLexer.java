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

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({
  "all",
  "warnings",
  "unchecked",
  "unused",
  "cast",
  "CheckReturnValue",
  "this-escape"
})
public class CrontabLexer extends Lexer {
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
  public static String[] channelNames = {"DEFAULT_TOKEN_CHANNEL", "HIDDEN"};

  public static String[] modeNames = {"DEFAULT_MODE"};

  private static String[] makeRuleNames() {
    return new String[] {
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

  public CrontabLexer(CharStream input) {
    super(input);
    _interp = new LexerATNSimulator(this, _ATN, _decisionToDFA, _sharedContextCache);
  }

  @Override
  public String getGrammarFileName() {
    return "CrontabLexer.g4";
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
  public String[] getChannelNames() {
    return channelNames;
  }

  @Override
  public String[] getModeNames() {
    return modeNames;
  }

  @Override
  public ATN getATN() {
    return _ATN;
  }

  public static final String _serializedATN =
      "\u0004\u0000\u0011r\u0006\uffff\uffff\u0002\u0000\u0007\u0000\u0002\u0001"
          + "\u0007\u0001\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004"
          + "\u0007\u0004\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007"
          + "\u0007\u0007\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b"
          + "\u0007\u000b\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e\u0002"
          + "\u000f\u0007\u000f\u0002\u0010\u0007\u0010\u0001\u0000\u0004\u0000%\b"
          + "\u0000\u000b\u0000\f\u0000&\u0001\u0001\u0004\u0001*\b\u0001\u000b\u0001"
          + "\f\u0001+\u0001\u0002\u0001\u0002\u0001\u0003\u0001\u0003\u0001\u0004"
          + "\u0001\u0004\u0001\u0005\u0001\u0005\u0001\u0006\u0001\u0006\u0001\u0007"
          + "\u0001\u0007\u0001\b\u0001\b\u0001\t\u0001\t\u0001\n\u0001\n\u0001\n\u0001"
          + "\n\u0001\n\u0001\n\u0001\n\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"
          + "\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\f\u0001"
          + "\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\f\u0001\r\u0001\r\u0001"
          + "\r\u0001\r\u0001\r\u0001\r\u0001\r\u0001\u000e\u0001\u000e\u0001\u000e"
          + "\u0001\u000e\u0001\u000e\u0001\u000e\u0001\u000f\u0001\u000f\u0001\u000f"
          + "\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f\u0001\u000f"
          + "\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0010\u0001\u0010"
          + "\u0001\u0010\u0000\u0000\u0011\u0001\u0001\u0003\u0002\u0005\u0003\u0007"
          + "\u0004\t\u0005\u000b\u0006\r\u0007\u000f\b\u0011\t\u0013\n\u0015\u000b"
          + "\u0017\f\u0019\r\u001b\u000e\u001d\u000f\u001f\u0010!\u0011\u0001\u0000"
          + "\u0001\u0002\u0000\t\t  s\u0000\u0001\u0001\u0000\u0000\u0000\u0000\u0003"
          + "\u0001\u0000\u0000\u0000\u0000\u0005\u0001\u0000\u0000\u0000\u0000\u0007"
          + "\u0001\u0000\u0000\u0000\u0000\t\u0001\u0000\u0000\u0000\u0000\u000b\u0001"
          + "\u0000\u0000\u0000\u0000\r\u0001\u0000\u0000\u0000\u0000\u000f\u0001\u0000"
          + "\u0000\u0000\u0000\u0011\u0001\u0000\u0000\u0000\u0000\u0013\u0001\u0000"
          + "\u0000\u0000\u0000\u0015\u0001\u0000\u0000\u0000\u0000\u0017\u0001\u0000"
          + "\u0000\u0000\u0000\u0019\u0001\u0000\u0000\u0000\u0000\u001b\u0001\u0000"
          + "\u0000\u0000\u0000\u001d\u0001\u0000\u0000\u0000\u0000\u001f\u0001\u0000"
          + "\u0000\u0000\u0000!\u0001\u0000\u0000\u0000\u0001$\u0001\u0000\u0000\u0000"
          + "\u0003)\u0001\u0000\u0000\u0000\u0005-\u0001\u0000\u0000\u0000\u0007/"
          + "\u0001\u0000\u0000\u0000\t1\u0001\u0000\u0000\u0000\u000b3\u0001\u0000"
          + "\u0000\u0000\r5\u0001\u0000\u0000\u0000\u000f7\u0001\u0000\u0000\u0000"
          + "\u00119\u0001\u0000\u0000\u0000\u0013;\u0001\u0000\u0000\u0000\u0015="
          + "\u0001\u0000\u0000\u0000\u0017D\u0001\u0000\u0000\u0000\u0019M\u0001\u0000"
          + "\u0000\u0000\u001bU\u0001\u0000\u0000\u0000\u001d\\\u0001\u0000\u0000"
          + "\u0000\u001fb\u0001\u0000\u0000\u0000!k\u0001\u0000\u0000\u0000#%\u0002"
          + "09\u0000$#\u0001\u0000\u0000\u0000%&\u0001\u0000\u0000\u0000&$\u0001\u0000"
          + "\u0000\u0000&\'\u0001\u0000\u0000\u0000\'\u0002\u0001\u0000\u0000\u0000"
          + "(*\u0007\u0000\u0000\u0000)(\u0001\u0000\u0000\u0000*+\u0001\u0000\u0000"
          + "\u0000+)\u0001\u0000\u0000\u0000+,\u0001\u0000\u0000\u0000,\u0004\u0001"
          + "\u0000\u0000\u0000-.\u0005-\u0000\u0000.\u0006\u0001\u0000\u0000\u0000"
          + "/0\u0005*\u0000\u00000\b\u0001\u0000\u0000\u000012\u0005/\u0000\u0000"
          + "2\n\u0001\u0000\u0000\u000034\u0005,\u0000\u00004\f\u0001\u0000\u0000"
          + "\u000056\u0005@\u0000\u00006\u000e\u0001\u0000\u0000\u000078\u0005H\u0000"
          + "\u00008\u0010\u0001\u0000\u0000\u00009:\u0005(\u0000\u0000:\u0012\u0001"
          + "\u0000\u0000\u0000;<\u0005)\u0000\u0000<\u0014\u0001\u0000\u0000\u0000"
          + "=>\u0005y\u0000\u0000>?\u0005e\u0000\u0000?@\u0005a\u0000\u0000@A\u0005"
          + "r\u0000\u0000AB\u0005l\u0000\u0000BC\u0005y\u0000\u0000C\u0016\u0001\u0000"
          + "\u0000\u0000DE\u0005a\u0000\u0000EF\u0005n\u0000\u0000FG\u0005n\u0000"
          + "\u0000GH\u0005u\u0000\u0000HI\u0005a\u0000\u0000IJ\u0005l\u0000\u0000"
          + "JK\u0005l\u0000\u0000KL\u0005y\u0000\u0000L\u0018\u0001\u0000\u0000\u0000"
          + "MN\u0005m\u0000\u0000NO\u0005o\u0000\u0000OP\u0005n\u0000\u0000PQ\u0005"
          + "t\u0000\u0000QR\u0005h\u0000\u0000RS\u0005l\u0000\u0000ST\u0005y\u0000"
          + "\u0000T\u001a\u0001\u0000\u0000\u0000UV\u0005w\u0000\u0000VW\u0005e\u0000"
          + "\u0000WX\u0005e\u0000\u0000XY\u0005k\u0000\u0000YZ\u0005l\u0000\u0000"
          + "Z[\u0005y\u0000\u0000[\u001c\u0001\u0000\u0000\u0000\\]\u0005d\u0000\u0000"
          + "]^\u0005a\u0000\u0000^_\u0005i\u0000\u0000_`\u0005l\u0000\u0000`a\u0005"
          + "y\u0000\u0000a\u001e\u0001\u0000\u0000\u0000bc\u0005m\u0000\u0000cd\u0005"
          + "i\u0000\u0000de\u0005d\u0000\u0000ef\u0005n\u0000\u0000fg\u0005i\u0000"
          + "\u0000gh\u0005g\u0000\u0000hi\u0005h\u0000\u0000ij\u0005t\u0000\u0000"
          + "j \u0001\u0000\u0000\u0000kl\u0005h\u0000\u0000lm\u0005o\u0000\u0000m"
          + "n\u0005u\u0000\u0000no\u0005r\u0000\u0000op\u0005l\u0000\u0000pq\u0005"
          + "y\u0000\u0000q\"\u0001\u0000\u0000\u0000\u0003\u0000&+\u0000";
  public static final ATN _ATN = new ATNDeserializer().deserialize(_serializedATN.toCharArray());

  static {
    _decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
    for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
      _decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
    }
  }
}
