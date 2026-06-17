/*
 * Adapted from an MIT-licensed cron-scheduling implementation.
 *
 * Modifications vs upstream:
 *  - Repackaged to io.adaptiq.titan.trigger.cron.internal.
 *  - Replaced the upstream ANTLR error listener with the in-package
 *    InlineANTLRErrorListener (throws IllegalArgumentException instead of the
 *    legacy ANTLR v2 exception type, which is not on this classpath).
 *  - Inlined the i18n message templates as English strings.
 *  - Dropped lexer.setLine(line) — not available on the stock ANTLR4 Lexer base;
 *    line numbers in syntax errors flow through the parser's own line tracking.
 *  - Removed nullness annotations (no spotbugs-annotations on this package).
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

import static java.util.Calendar.DAY_OF_MONTH;
import static java.util.Calendar.HOUR_OF_DAY;
import static java.util.Calendar.MINUTE;
import static java.util.Calendar.MONTH;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

/** Table for driving scheduled tasks. */
public final class CronTab {
  /**
   * bits[0]: minutes bits[1]: hours bits[2]: days bits[3]: months
   *
   * <p>false:not scheduled &lt;-&gt; true scheduled
   */
  final long[] bits = new long[4];

  int dayOfWeek;

  /** Textual representation. */
  private String spec;

  /** Optional timezone string for calendar */
  private String specTimezone;

  public CronTab(String format) {
    this(format, null);
  }

  public CronTab(String format, Hash hash) {
    this(format, 1, hash);
  }

  @Deprecated(since = "1.448")
  public CronTab(String format, int line) {
    set(format, line, null);
  }

  public CronTab(String format, int line, Hash hash) {
    this(format, line, hash, null);
  }

  public CronTab(String format, int line, Hash hash, String timezone) {
    set(format, line, hash, timezone);
  }

  private void set(String format, int line, Hash hash) {
    set(format, line, hash, null);
  }

  private void set(String format, int line, Hash hash, String timezone) {
    CrontabLexer lexer = new CrontabLexer(CharStreams.fromString(format));
    lexer.removeErrorListeners();
    lexer.addErrorListener(new InlineANTLRErrorListener());
    CrontabParser parser = new CrontabParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(new InlineANTLRErrorListener(parser::getErrorMessage));
    parser.setHash(hash);
    spec = format;
    specTimezone = timezone;

    parser.startRule(this);
    if ((dayOfWeek & (1 << 7)) != 0) {
      dayOfWeek |= 1; // copy bit 7 over to bit 0
      dayOfWeek &= ~(1 << 7); // clear bit 7 or CalendarField#ceil will return an invalid value 7
    }
  }

  /** Returns true if the given calendar matches */
  boolean check(Calendar cal) {

    Calendar checkCal = cal;

    if (specTimezone != null && !specTimezone.isEmpty()) {
      Calendar tzCal = Calendar.getInstance(TimeZone.getTimeZone(specTimezone));
      tzCal.setTime(cal.getTime());
      checkCal = tzCal;
    }

    if (!checkBits(bits[0], checkCal.get(MINUTE))) return false;
    if (!checkBits(bits[1], checkCal.get(HOUR_OF_DAY))) return false;
    if (!checkBits(bits[2], checkCal.get(DAY_OF_MONTH))) return false;
    if (!checkBits(bits[3], checkCal.get(MONTH) + 1)) return false;
    if (!checkBits(dayOfWeek, checkCal.get(Calendar.DAY_OF_WEEK) - 1)) return false;

    return true;
  }

  private abstract static class CalendarField {
    final int field;
    final CalendarField lowerField;
    final int offset;
    final int min;
    final boolean redoAdjustmentIfModified;

    @SuppressWarnings("unused")
    private final String displayName;

    private CalendarField(
        String displayName,
        int field,
        int min,
        int offset,
        boolean redoAdjustmentIfModified,
        CalendarField lowerField) {
      this.displayName = displayName;
      this.field = field;
      this.min = min;
      this.redoAdjustmentIfModified = redoAdjustmentIfModified;
      this.lowerField = lowerField;
      this.offset = offset;
    }

    int valueOf(Calendar c) {
      return c.get(field) + offset;
    }

    void addTo(Calendar c, int i) {
      c.add(field, i);
    }

    void setTo(Calendar c, int i) {
      c.set(field, Math.min(i - offset, c.getActualMaximum(field)));
    }

    void clear(Calendar c) {
      setTo(c, min);
    }

    private int ceil(CronTab c, int n) {
      long bits = bits(c);
      while ((bits | (1L << n)) != bits) {
        if (n > 60) return -1;
        n++;
      }
      return n;
    }

    private int first(CronTab c) {
      return ceil(c, 0);
    }

    private int floor(CronTab c, int n) {
      long bits = bits(c);
      while ((bits | (1L << n)) != bits) {
        if (n == 0) return -1;
        n--;
      }
      return n;
    }

    private int last(CronTab c) {
      return floor(c, 63);
    }

    abstract long bits(CronTab c);

    abstract void rollUp(Calendar cal, int i);

    private static final CalendarField MINUTE =
        new CalendarField("minute", Calendar.MINUTE, 0, 0, false, null) {
          @Override
          long bits(CronTab c) {
            return c.bits[0];
          }

          @Override
          void rollUp(Calendar cal, int i) {
            cal.add(Calendar.HOUR_OF_DAY, i);
          }
        };
    private static final CalendarField HOUR =
        new CalendarField("hour", Calendar.HOUR_OF_DAY, 0, 0, false, MINUTE) {
          @Override
          long bits(CronTab c) {
            return c.bits[1];
          }

          @Override
          void rollUp(Calendar cal, int i) {
            cal.add(Calendar.DAY_OF_MONTH, i);
          }
        };
    private static final CalendarField DAY_OF_MONTH =
        new CalendarField("day", Calendar.DAY_OF_MONTH, 1, 0, true, HOUR) {
          @Override
          long bits(CronTab c) {
            return c.bits[2];
          }

          @Override
          void rollUp(Calendar cal, int i) {
            cal.add(Calendar.MONTH, i);
          }
        };
    private static final CalendarField MONTH =
        new CalendarField("month", Calendar.MONTH, 1, 1, false, DAY_OF_MONTH) {
          @Override
          long bits(CronTab c) {
            return c.bits[3];
          }

          @Override
          void rollUp(Calendar cal, int i) {
            cal.add(Calendar.YEAR, i);
          }
        };
    private static final CalendarField DAY_OF_WEEK =
        new CalendarField("dow", Calendar.DAY_OF_WEEK, 1, -1, true, HOUR) {
          @Override
          long bits(CronTab c) {
            return c.dayOfWeek;
          }

          @Override
          void rollUp(Calendar cal, int i) {
            cal.add(Calendar.DAY_OF_WEEK, 7 * i);
          }

          @Override
          void setTo(Calendar c, int i) {
            int v = i - offset;
            int was = c.get(field);
            c.set(field, v);
            final int firstDayOfWeek = c.getFirstDayOfWeek();
            if (v < firstDayOfWeek && was >= firstDayOfWeek) {
              addTo(c, -7);
            } else if (was < firstDayOfWeek && firstDayOfWeek <= v) {
              addTo(c, 7);
            }
          }
        };

    private static final CalendarField[] ADJUST_ORDER = {
      MONTH, DAY_OF_MONTH, DAY_OF_WEEK, HOUR, MINUTE,
    };
  }

  public Calendar ceil(long t) {
    Calendar cal = new GregorianCalendar(Locale.US);
    cal.setTimeInMillis(t);
    return ceil(cal);
  }

  public Calendar ceil(Calendar cal) {
    if (cal.get(Calendar.SECOND) > 0 || cal.get(Calendar.MILLISECOND) > 0) {
      cal.set(Calendar.SECOND, 0);
      cal.set(Calendar.MILLISECOND, 0);
      cal.add(Calendar.MINUTE, 1);
    }
    Calendar twoYearsFuture = (Calendar) cal.clone();
    twoYearsFuture.add(Calendar.YEAR, 2);
    OUTER:
    while (true) {
      if (cal.compareTo(twoYearsFuture) > 0) {
        throw new RareOrImpossibleDateException();
      }
      for (CalendarField f : CalendarField.ADJUST_ORDER) {
        int cur = f.valueOf(cal);
        int next = f.ceil(this, cur);
        if (cur == next) continue;

        for (CalendarField l = f.lowerField; l != null; l = l.lowerField) l.clear(cal);

        if (next < 0) {
          f.rollUp(cal, 1);
          f.setTo(cal, f.first(this));
          continue OUTER;
        } else {
          f.setTo(cal, next);
          if (f.valueOf(cal) != next) {
            f.rollUp(cal, 1);
            f.setTo(cal, f.first(this));
            continue OUTER;
          }
          if (f.redoAdjustmentIfModified) continue OUTER;
        }
      }
      return cal;
    }
  }

  public Calendar floor(long t) {
    Calendar cal = new GregorianCalendar(Locale.US);
    cal.setTimeInMillis(t);
    return floor(cal);
  }

  public Calendar floor(Calendar cal) {
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    Calendar twoYearsAgo = (Calendar) cal.clone();
    twoYearsAgo.add(Calendar.YEAR, -2);

    OUTER:
    while (true) {
      if (cal.compareTo(twoYearsAgo) < 0) {
        throw new RareOrImpossibleDateException();
      }
      for (CalendarField f : CalendarField.ADJUST_ORDER) {
        int cur = f.valueOf(cal);
        int next = f.floor(this, cur);
        if (cur == next) continue;

        for (CalendarField l = f.lowerField; l != null; l = l.lowerField) l.clear(cal);

        if (next < 0) {
          f.rollUp(cal, -1);
          f.setTo(cal, f.last(this));
          f.addTo(cal, 1);
          CalendarField.MINUTE.addTo(cal, -1);
          continue OUTER;
        } else {
          f.setTo(cal, next);
          f.addTo(cal, 1);
          CalendarField.MINUTE.addTo(cal, -1);
          if (f.redoAdjustmentIfModified) continue OUTER;
        }
      }
      return cal;
    }
  }

  void set(String format, Hash hash) {
    set(format, 1, hash);
  }

  private boolean checkBits(long bitMask, int n) {
    return (bitMask | (1L << n)) == bitMask;
  }

  @Override
  public String toString() {
    return super.toString()
        + "["
        + toString("minute", bits[0])
        + ','
        + toString("hour", bits[1])
        + ','
        + toString("dayOfMonth", bits[2])
        + ','
        + toString("month", bits[3])
        + ','
        + toString("dayOfWeek", dayOfWeek)
        + ']';
  }

  private String toString(String key, long bit) {
    return key + '=' + Long.toHexString(bit);
  }

  public String checkSanity() {
    OUTER:
    for (int i = 0; i < 5; i++) {
      long bitMask = i < 4 ? bits[i] : (long) dayOfWeek;
      for (int j = BaseParser.LOWER_BOUNDS[i]; j <= BaseParser.UPPER_BOUNDS[i]; j++) {
        if (!checkBits(bitMask, j)) {
          if (i > 0)
            return "Do you really mean \"every minute\" when you say \""
                + spec
                + "\"? Perhaps you meant \"H "
                + spec.substring(spec.indexOf(' ') + 1)
                + "\" to poll once per hour";
          break OUTER;
        }
      }
    }

    int daysOfMonth = 0;
    for (int i = 1; i < 31; i++) {
      if (checkBits(bits[2], i)) {
        daysOfMonth++;
      }
    }
    if (daysOfMonth > 5 && daysOfMonth < 28) {
      return "Short cycles in the day-of-month field will behave oddly near the end of a month";
    }

    String hashified = hashify(spec);
    if (hashified != null) {
      return "Spread load evenly by using \"" + hashified + "\" rather than \"" + spec + "\"";
    }

    return null;
  }

  public static String hashify(String spec) {
    if (spec.contains("H")) {
      return null;
    } else if (spec.startsWith("*/")) {
      return "H" + spec.substring(1);
    } else if (spec.matches("\\d+ .+")) {
      return "H " + spec.substring(spec.indexOf(' ') + 1);
    } else {
      Matcher m = Pattern.compile("0(,(\\d+)(,\\d+)*)( .+)").matcher(spec);
      if (m.matches()) {
        int period = Integer.parseInt(m.group(2));
        if (period > 0) {
          StringBuilder b = new StringBuilder();
          for (int i = period; i < 60; i += period) {
            b.append(',').append(i);
          }
          if (b.toString().equals(m.group(1))) {
            return "H/" + period + m.group(4);
          }
        }
      }
      return null;
    }
  }

  public TimeZone getTimeZone() {
    if (this.specTimezone == null) {
      return null;
    }
    return TimeZone.getTimeZone(this.specTimezone);
  }
}
