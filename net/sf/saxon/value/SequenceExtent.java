////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.value;

import net.sf.saxon.expr.LastPositionFinder;
import net.sf.saxon.expr.StaticProperty;
import net.sf.saxon.expr.parser.ExpressionTool;
import net.sf.saxon.om.*;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.iter.GroundedIterator;
import net.sf.saxon.tree.iter.ListIterator;
import net.sf.saxon.tree.iter.ReverseListIterator;
import net.sf.saxon.tree.iter.UnfailingIterator;
import net.sf.saxon.tree.util.FastStringBuffer;

import java.util.*;


/**
 * A sequence value implemented extensionally. That is, this class represents a sequence
 * by allocating memory to each item in the sequence.
 */

public class SequenceExtent<T extends Item<?>> implements GroundedValue<T> {
    private List<T> value;

    /**
     * Construct an sequence from an array of items. Note, the array of items is used as is,
     * which means the caller must not subsequently change its contents.
     *
     * @param items the array of items to be included in the sequence
     */

    public SequenceExtent(T[] items) {
        value = Arrays.asList(items);
    }

    /**
     * Construct a SequenceExtent as a view of another SequenceExtent
     *
     * @param ext    The existing SequenceExtent
     * @param start  zero-based offset of the first item in the existing SequenceExtent
     *               that is to be included in the new SequenceExtent
     * @param length The number of items in the new SequenceExtent
     */

    public SequenceExtent(SequenceExtent<T> ext, int start, int length) {
        value = ext.value.subList(start, start+length);
    }

    /**
     * Construct a SequenceExtent from a List. The members of the list must all
     * be Items
     *
     * @param list the list of items to be included in the sequence
     */

    public SequenceExtent(List<T> list) {
        this.value = list;
    }

    /**
     * Construct a sequence containing all the remaining items in a SequenceIterator.
     *
     * @param iter The supplied sequence of items. The returned sequence will contain all
     *             items delivered by repeated calls on next() on this iterator, and the
     *             iterator will be consumed by calling the method.
     * @throws net.sf.saxon.trans.XPathException
     *          if reading the items using the
     *          SequenceIterator raises an error
     */

    public SequenceExtent(SequenceIterator<T> iter) throws XPathException {
        int len = (iter.getProperties() & SequenceIterator.LAST_POSITION_FINDER) == 0
                ? 20
                : ((LastPositionFinder)iter).getLength();
        List<T> list = new ArrayList<>(len);
        iter.forEachOrFail(list::add);
        value = list;
    }

    /**
     * Factory method to make a GroundedValue holding the contents of any SequenceIterator.
     *
     * @param iter a Sequence iterator that may or may not be consumed to deliver the items in the sequence.
     *             The iterator must be positioned at the start.
     * @return a GroundedValue holding the items delivered by the SequenceIterator. If the
     *         sequence is empty the result will be an instance of {@link EmptySequence}. If it is of length
     *         one, the result will be an {@link Item}. In all other cases, it will be an instance of
     *         {@link SequenceExtent}.
     * @throws net.sf.saxon.trans.XPathException
     *          if an error occurs processing the values from
     *          the iterator.
     * @deprecated since 9.9: use {@link SequenceIterator#materialize()}
     */

    public static <T extends Item<?>> GroundedValue<? extends T> makeSequenceExtent(SequenceIterator<T> iter) throws XPathException {
        return iter.materialize();
    }

    /**
     * Factory method to make a GroundedValue holding the contents of any SequenceIterator.
     *
     * @param iter a Sequence iterator that may or may not be consumed to deliver the items in the sequence.
     *             The iterator must be positioned at the start.
     * @return a GroundedValue holding the items delivered by the SequenceIterator. If the
     * sequence is empty the result will be an instance of {@link EmptySequence}. If it is of length
     * one, the result will be an {@link Item}. In other cases, the actual implementation class
     * is not defined.
     * <p>This method is intended primarily for internal use. For external applications, calling
     * {@link SequenceIterator#materialize()} is preferable, because it recognizes cases that can
     * be optimized (notably, cases where the iterator is already backed by an array or list in memory).</p>
     *
     * @throws net.sf.saxon.trans.XPathException if an error occurs processing the values from
     *                                           the iterator.
     * @deprecated since 9.9: use {@link SequenceIterator#materialize()}
     */

    public static <T extends Item<?>> GroundedValue<T> fromIterator(SequenceIterator<T> iter) throws XPathException {
        return new SequenceExtent<>(iter).reduce();
    }

    /**
     * Factory method to make a GroundedValue holding the remaining contents of any SequenceIterator,
     * that is, the contents that have not yet been read
     *
     * @param iter a Sequence iterator that may or may not be consumed to deliver the items in the sequence.
     *             The iterator need not be positioned at the start.
     * @return a GroundedValue holding the items delivered by the SequenceIterator. If the
     * sequence is empty the result will be an instance of {@link EmptySequence}. If it is of length
     * one, the result will be an {@link Item}. In all other cases, it will be an instance of
     * {@link SequenceExtent}.
     * @throws net.sf.saxon.trans.XPathException if an error occurs processing the values from
     *                                           the iterator.
     */

    public static <T extends Item<?>> GroundedValue<T> makeResidue(SequenceIterator<T> iter) throws XPathException {
        if ((iter.getProperties() & SequenceIterator.GROUNDED) != 0) {
            return ((GroundedIterator) iter).getResidue();
        }
        SequenceExtent<T> extent = new SequenceExtent<>(iter);
        return extent.reduce();
    }


    /**
     * Factory method to make a Value holding the contents of any List of items
     *
     * @param input a List containing the items in the sequence
     * @return a ValueRepresentation holding the items in the list. If the
     *         sequence is empty the result will be an instance of {@link EmptySequence}. If it is of length
     *         one, the result will be an {@link Item}. In all other cases, it will be an instance of
     *         {@link SequenceExtent}.
     */

    public static <T extends Item<?>> GroundedValue<T> makeSequenceExtent(/*@NotNull*/ List<T> input) {
        int len = input.size();
        if (len == 0) {
            return EmptySequence.getInstance();
        } else if (len == 1) {
            return (GroundedValue<T>)input.get(0);
        } else {
            return new SequenceExtent<>(input);
        }
    }

    public String getStringValue() throws XPathException {
        return SequenceTool.getStringValue(this);
    }

    public CharSequence getStringValueCS() throws XPathException {
        return SequenceTool.getStringValue(this);
    }

    /**
     * Get the first item in the sequence.
     *
     * @return the first item in the sequence if there is one, or null if the sequence
     *         is empty
     */
    public T head() {
        return itemAt(0);
    }

    /**
     * Get the number of items in the sequence
     *
     * @return the number of items in the sequence
     */

    public int getLength() {
        return value.size();
    }

    /**
     * Determine the cardinality
     *
     * @return the cardinality of the sequence, using the constants defined in
     *         net.sf.saxon.value.Cardinality
     * @see net.sf.saxon.value.Cardinality
     */

    public int getCardinality() {
        switch (value.size()) {
            case 0:
                return StaticProperty.EMPTY;
            case 1:
                return StaticProperty.EXACTLY_ONE;
            default:
                return StaticProperty.ALLOWS_ONE_OR_MORE;
        }
    }

    /**
     * Get the n'th item in the sequence (starting with 0 as the first item)
     *
     * @param n the position of the required item
     * @return the n'th item in the sequence, or null if the position is out of range
     */

    /*@Nullable*/
    public T itemAt(int n) {
        if (n < 0 || n >= getLength()) {
            return null;
        } else {
            return value.get(n);
        }
    }

    /**
     * Return an iterator over this sequence.
     *
     * @return the required SequenceIterator, positioned at the start of the
     *         sequence
     */

    /*@NotNull*/
    public ListIterator<T> iterate() {
        return new ListIterator<>(value);
    }

    /**
     * Return an enumeration of this sequence in reverse order (used for reverse axes)
     *
     * @return an AxisIterator that processes the items in reverse order
     */

    /*@NotNull*/
    public UnfailingIterator<T> reverseIterate() {
        return new ReverseListIterator<>(value);
    }

    /**
     * Get the effective boolean value
     */

    public boolean effectiveBooleanValue() throws XPathException {
        int len = getLength();
        if (len == 0) {
            return false;
        } else {
            Item first = value.get(0);
            if (first instanceof NodeInfo) {
                return true;
            } else if (len == 1 && first instanceof AtomicValue) {
                return first.effectiveBooleanValue();
            } else {
                // this will fail - reuse the error messages
                return ExpressionTool.effectiveBooleanValue(iterate());
            }
        }
    }


    /**
     * Get a subsequence of the value
     *
     * @param start  the index of the first item to be included in the result, counting from zero.
     *               A negative value is taken as zero. If the value is beyond the end of the sequence, an empty
     *               sequence is returned
     * @param length the number of items to be included in the result. Specify Integer.MAX_VALUE to
     *               get the subsequence up to the end of the base sequence. If the value is negative, an empty sequence
     *               is returned. If the value goes off the end of the sequence, the result returns items up to the end
     *               of the sequence
     * @return the required subsequence. If min is
     */

    /*@NotNull*/
    public GroundedValue<T> subsequence(int start, int length) {
        int end = value.size();
        if (start < 0) {
            start = 0;
        } else if (start >= end) {
            return EmptySequence.getInstance();
        }
        int newStart = start;
        int newEnd;
        if (length > end) {
            newEnd = end;
        } else if (length < 0) {
            return EmptySequence.getInstance();
        } else {
            newEnd = newStart + length;
            if (newEnd > end) {
                newEnd = end;
            }
        }
        return new SequenceExtent<>(value.subList(newStart, newEnd));
    }

    /*@NotNull*/
    public String toString() {
        FastStringBuffer fsb = new FastStringBuffer(FastStringBuffer.C64);
        for (int i = 0; i < value.size(); i++) {
            fsb.append(i == 0 ? "(" : ", ");
            fsb.append(value.get(i).toString());
        }
        fsb.append(')');
        return fsb.toString();
    }

    /**
     * Reduce the sequence to its simplest form. If the value is an empty sequence, the result will be
     * EmptySequence.getInstance(). If the value is a single atomic value, the result will be an instance
     * of AtomicValue. If the value is a single item of any other kind, the result will be an instance
     * of One. Otherwise, the result will typically be unchanged.
     *
     * @return the simplified sequence
     */
    public GroundedValue<T> reduce() {
        int len = getLength();
        if (len == 0) {
            return EmptySequence.getInstance();
        } else if (len == 1) {
            return (GroundedValue<T>)itemAt(0);
        } else {
            return this;
        }
    }

    /**
     * Get the contents of this value in the form of a Java {@link java.lang.Iterable},
     * so that it can be used in a for-each expression
     * @return an Iterable containing the same sequence of items
     */

    public Iterable<T> asIterable() {
        return value;
    }


    /**
     * Get an iterator (a Java {@link java.util.Iterator}) over the items in this sequence.
     * @return an iterator over the items in this sequence.
     */

    @Override
    public Iterator<T> iterator() {
        return value.iterator();
    }
}

