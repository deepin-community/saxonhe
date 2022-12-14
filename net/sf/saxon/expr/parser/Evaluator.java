////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.expr.parser;

import net.sf.saxon.Controller;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.event.SequenceOutputter;
import net.sf.saxon.expr.*;
import net.sf.saxon.expr.instruct.Block;
import net.sf.saxon.om.*;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.*;

import java.util.ArrayList;
import java.util.List;

/**
 * An Evaluator evaluates an expression to return a sequence
 */
public abstract class Evaluator {


    public static Evaluator evaluatorForCode(int code) {
        switch (code) {
            case ExpressionTool.EVALUATE_LITERAL:
                return Evaluator.LITERAL;
            case ExpressionTool.EVALUATE_VARIABLE:
                return Evaluator.VARIABLE;
            case ExpressionTool.MAKE_CLOSURE:
                return Evaluator.LAZY_SEQUENCE;
            case ExpressionTool.MAKE_MEMO_CLOSURE:
                return Evaluator.MEMO_CLOSURE;
            case ExpressionTool.RETURN_EMPTY_SEQUENCE:
                return Evaluator.EMPTY_SEQUENCE;
            case ExpressionTool.EVALUATE_AND_MATERIALIZE_VARIABLE:
                return Evaluator.VARIABLE;
            case ExpressionTool.CALL_EVALUATE_SINGLE_ITEM:
                return Evaluator.SINGLE_ITEM;
            case ExpressionTool.CALL_EVALUATE_OPTIONAL_ITEM:
                return Evaluator.OPTIONAL_ITEM;
            case ExpressionTool.ITERATE_AND_MATERIALIZE:
                return Evaluator.EAGER_SEQUENCE;
            case ExpressionTool.PROCESS:
                return Evaluator.PROCESS;
            case ExpressionTool.LAZY_TAIL_EXPRESSION:
                return Evaluator.LAZY_TAIL;
            case ExpressionTool.SHARED_APPEND_EXPRESSION:
                return Evaluator.SHARED_APPEND;
            case ExpressionTool.MAKE_INDEXED_VARIABLE:
                return Evaluator.MAKE_INDEXED_VARIABLE;
            case ExpressionTool.MAKE_SINGLETON_CLOSURE:
                return Evaluator.SINGLETON_CLOSURE;
            case ExpressionTool.EVALUATE_SUPPLIED_PARAMETER:
                return Evaluator.SUPPLIED_PARAMETER;
            case ExpressionTool.STREAMING_ARGUMENT:
                return Evaluator.STREAMING_ARGUMENT;
            default:
                return Evaluator.EAGER_SEQUENCE;
        }
    }

    /**
     * Evaluate an expression to return a sequence
     * @param expr the expression to be evaluated
     * @param context the dynamic context for evaluation
     * @return the result of the evaluation
     * @throws XPathException if any dynamic error occurs during the evaluation
     */

    public abstract Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException;

    /**
     * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
     * to be compatible with argument evaluation modes in earlier releases in the interests of
     * SEF compatibility.
     */

    public abstract int getCode();

    /**
     * An evaluator that always returns the empty sequence
     */

    public final static Evaluator EMPTY_SEQUENCE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) {
            return EmptySequence.getInstance();
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.RETURN_EMPTY_SEQUENCE;
        }
    };

    /**
     * An evaluator for arguments supplied as a literal
     */

    public final static Evaluator LITERAL = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) {
            return ((Literal)expr).getValue();
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.EVALUATE_LITERAL;
        }
    };

    /**
     * An evaluator for arguments supplied as a variable reference
     */

    public final static Evaluator VARIABLE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            try {
                return ((VariableReference) expr).evaluateVariable(context);
            } catch (ClassCastException e) {
                // should not happen
                assert false;
                // but if it does...
                return LAZY_SEQUENCE.evaluate(expr, context);
            }
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.EVALUATE_AND_MATERIALIZE_VARIABLE;
        }
    };

    /**
     * An evaluator for a reference to an external parameter value
     */

    public final static Evaluator SUPPLIED_PARAMETER = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            try {
                return ((SuppliedParameterReference) expr).evaluateVariable(context);
            } catch (ClassCastException e) {
                // should not happen
                assert false;
                // but if it does...
                return LAZY_SEQUENCE.evaluate(expr, context);
            }
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.EVALUATE_SUPPLIED_PARAMETER;
        }
    };

    /**
     * A (default) evaluator for arguments supplied as an expression that will always return a
     * singleton item
     */

    public final static Evaluator SINGLE_ITEM = new Evaluator() {
        @Override
        public Item<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            return expr.evaluateItem(context);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.CALL_EVALUATE_SINGLE_ITEM;
        }
    };

    /**
     * A (default) evaluator for arguments supplied as an expression that will return either a
     * singleton item, or an empty sequence
     */

    public final static Evaluator OPTIONAL_ITEM = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            Item<?> item = expr.evaluateItem(context);
            return item==null ? EmptySequence.getInstance() : item;
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.CALL_EVALUATE_OPTIONAL_ITEM;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * lazily on first use. This is appropriate when calling a function which might not use the value, or
     * might not use all of it. It returns a {@code LazySequence}, which can only be read once, so
     * this is only suitable for use when calling a function that can be trusted to read the argument
     * once only.
     */

    public final static Evaluator LAZY_SEQUENCE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            SequenceIterator<?> iter = expr.iterate(context);
            return new LazySequence<>(iter);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.MAKE_CLOSURE;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * lazily on first use, and where the value might be needed more than once.
     */

    public final static Evaluator MEMO_SEQUENCE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            SequenceIterator<?> iter = expr.iterate(context);
            return new MemoSequence<>(iter);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.MAKE_MEMO_CLOSURE;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * lazily on first use, and where the value might be needed more than once.
     */

    public final static Evaluator MEMO_CLOSURE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            return new MemoClosure(expr, context);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.MAKE_MEMO_CLOSURE;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * lazily on first use, and where the value might be needed more than once.
     */

    public final static Evaluator SINGLETON_CLOSURE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            return new SingletonClosure(expr, context);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.MAKE_SINGLETON_CLOSURE;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * eagerly. This is appropriate when it is known that the function will always use the entire value,
     * or when it will use it more than once.
     */

    public final static Evaluator EAGER_SEQUENCE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            SequenceIterator<?> iter = expr.iterate(context);
            return iter.materialize();
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.ITERATE_AND_MATERIALIZE;
        }
    };

    /**
     * An evaluator for "shared append" expressions: used when the argument to a function
     * is a block potentially containing a recursive call.
     */

    public final static Evaluator SHARED_APPEND = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            if (expr instanceof Block) {
                Block block = (Block) expr;
                Operand[] children = block.getOperanda();
                List<GroundedValue<?>> subsequences = new ArrayList<>(children.length);
                for (Operand o : children) {
                    Expression child = o.getChildExpression();
                    if (Cardinality.allowsMany(child.getCardinality())) {
                        subsequences.add(child.iterate(context).materialize());
                    } else {
                        Item<?> j = child.evaluateItem(context);
                        if (j != null) {
                            subsequences.add(j);
                        }
                    }
                }
                return new Chain(subsequences);
            } else {
                // it's not a Block: it must have been rewritten after deciding to use this evaluation mode
                return expr.iterate(context).materialize();
            }
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.SHARED_APPEND_EXPRESSION;
        }
    };

    /**
     * An evaluator for the first (streamed) argument of a streamable function call.
     */

    public final static Evaluator STREAMING_ARGUMENT = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            return context.getConfiguration().obtainOptimizer().evaluateStreamingArgument(expr, context);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.STREAMING_ARGUMENT;
        }
    };

    /**
     * An evaluator for an expression that makes use of an indexed variable
     */

    public final static Evaluator MAKE_INDEXED_VARIABLE = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            return context.getConfiguration().obtainOptimizer().makeIndexedValue(expr.iterate(context));
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.MAKE_INDEXED_VARIABLE;
        }
    };

    /**
     * A push-mode evaluator for an expression
     */

    public final static Evaluator PROCESS = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            Controller controller = context.getController();
            Receiver saved = context.getReceiver();
            SequenceOutputter seq = controller.allocateSequenceOutputter(20);
            seq.getPipelineConfiguration().setHostLanguage(expr.getPackageData().getHostLanguage());
            context.setReceiver(seq);
            seq.open();
            expr.process(context);
            seq.close();
            context.setReceiver(saved);
            Sequence<?> val = seq.getSequence();
            seq.reset();
            return val;
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.PROCESS;
        }
    };

    /**
     * An evaluator for arguments that in general return a sequence, where the sequence is evaluated
     * lazily on first use. This is appropriate when calling a function which might not use the value, or
     * might not use all of it. It returns a {@code LazySequence}, which can only be read once, so
     * this is only suitable for use when calling a function that can be trusted to read the argument
     * once only.
     */

    public final static Evaluator LAZY_TAIL = new Evaluator() {
        @Override
        public Sequence<?> evaluate(Expression expr, XPathContext context) throws XPathException {
            TailExpression tail = (TailExpression) expr;
            VariableReference vr = (VariableReference) tail.getBaseExpression();
            Sequence<?> base = Evaluator.VARIABLE.evaluate(vr, context);
            if (base instanceof MemoClosure) {
                SequenceIterator<?> it = base.iterate();
                base = it.materialize();
            }
            if (base instanceof IntegerRange) {
                long start = ((IntegerRange) base).getStart() + tail.getStart() - 1;
                long end = ((IntegerRange) base).getEnd();
                if (start == end) {
                    return Int64Value.makeIntegerValue(end);
                } else if (start > end) {
                    return EmptySequence.getInstance();
                } else {
                    return new IntegerRange(start, end);
                }
            }
            if (base instanceof SequenceExtent) {
                SequenceExtent<?> baseSeq = (SequenceExtent<?>)base;
                if (tail.getStart() > ((SequenceExtent) base).getLength()) {
                    return EmptySequence.getInstance();
                } else {
                    return new SequenceExtent<>(
                            baseSeq,
                            tail.getStart() - 1,
                            baseSeq.getLength() - tail.getStart() + 1).reduce();
                }
            }

            return new MemoClosure(tail, context);
        }

        /**
         * Get an integer code identifying this evaluator (for use in the SEF file). The codes are chosen
         * to be compatible with argument evaluation modes in earlier releases in the interests of
         * SEF compatibility.
         */
        @Override
        public int getCode() {
            return ExpressionTool.LAZY_TAIL_EXPRESSION;
        }
    };


}



