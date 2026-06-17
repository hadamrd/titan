/*
 * Adapted from an MIT-licensed cron-scheduling implementation.
 *
 * Modifications vs upstream:
 *  - Repackaged to io.adaptiq.titan.trigger.cron.internal.
 *  - Dropped the upstream access-restriction annotation — that library is not on
 *    the classpath.
 *
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
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

/**
 * Thrown when the previous/next occurrence of a cron expression is not found within the 2-year
 * look-around window — e.g. an impossible date like {@code 0 0 31 6 *} (June 31), or a date so rare
 * it isn't useful (Feb 29, or a multi-field intersection that intersects only every many years).
 */
public class RareOrImpossibleDateException extends RuntimeException {}
