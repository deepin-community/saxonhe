////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.expr.instruct;

import net.sf.saxon.Configuration;
import net.sf.saxon.event.*;
import net.sf.saxon.expr.*;
import net.sf.saxon.expr.parser.*;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.lib.SerializerFactory;
import net.sf.saxon.lib.StandardErrorListener;
import net.sf.saxon.om.*;
import net.sf.saxon.serialize.SerializationProperties;
import net.sf.saxon.trace.ExpressionPresenter;
import net.sf.saxon.trans.Err;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.trans.XsltController;
import net.sf.saxon.type.*;
import net.sf.saxon.value.StringValue;
import net.sf.saxon.value.Whitespace;

import javax.xml.transform.OutputKeys;

/**
 * An xsl:message element in the stylesheet.
 */

public class Message extends Instruction {

    private Operand selectOp;
    private Operand terminateOp;
    private Operand errorCodeOp;

    private boolean isAssert;

    /**
     * Create an xsl:message instruction
     *
     * @param select    the expression that constructs the message (composite of the select attribute
     *                  and the contained sequence constructor)
     * @param terminate expression that calculates terminate = yes or no.
     * @param errorCode expression used to compute the error code
     */

    public Message(Expression select, Expression terminate, Expression errorCode) {
        if (errorCode == null) {
            errorCode = new StringLiteral("Q{" + NamespaceConstant.ERR + "}XTMM9000");
        }
        selectOp = new Operand(this, select, OperandRole.SINGLE_ATOMIC);
        terminateOp = new Operand(this, terminate, OperandRole.SINGLE_ATOMIC);
        errorCodeOp = new Operand(this, errorCode, OperandRole.SINGLE_ATOMIC);
    }


    public Expression getSelect() {
        return selectOp.getChildExpression();
    }

    public void setSelect(Expression select) {
        selectOp.setChildExpression(select);
    }

    public Expression getTerminate() {
        return terminateOp.getChildExpression();
    }

    public void setTerminate(Expression terminate) {
        terminateOp.setChildExpression(terminate);
    }

    public Expression getErrorCode() {
        return errorCodeOp.getChildExpression();
    }

    public void setErrorCode(Expression errorCode) {
        errorCodeOp.setChildExpression(errorCode);
    }

    @Override
    public Iterable<Operand> operands() {
        return operandList(selectOp, terminateOp, errorCodeOp);
    }


    /**
     * Say whether this instruction is implementing xsl:message or xsl:assert
     *
     * @param isAssert true if this is xsl:assert; false if it is xsl:message
     */

    public void setIsAssert(boolean isAssert) {
        this.isAssert = isAssert;
    }

    /**
     * Copy an expression. This makes a deep copy.
     *
     * @param rebindings
     * @return the copy of the original expression
     */

    /*@NotNull*/
    public Expression copy(RebindingMap rebindings) {
        Message exp = new Message(getSelect().copy(rebindings), getTerminate().copy(rebindings), getErrorCode().copy(rebindings));
        ExpressionTool.copyLocationInfo(this, exp);
        return exp;
    }

    /**
     * Get the name of this instruction for diagnostic and tracing purposes
     */

    public int getInstructionNameCode() {
        return isAssert ? StandardNames.XSL_ASSERT : StandardNames.XSL_MESSAGE;
    }

    /**
     * Get the item type. To avoid spurious compile-time type errors, we falsely declare that the
     * instruction can return anything
     *
     * @return AnyItemType
     */
    /*@NotNull*/
    public ItemType getItemType() {
        return AnyItemType.getInstance();
    }

    /**
     * Get the static cardinality. To avoid spurious compile-time type errors, we falsely declare that the
     * instruction returns zero or one items - this is always acceptable
     *
     * @return zero or one
     */

    public int getCardinality() {
        return StaticProperty.ALLOWS_ZERO_OR_ONE;
    }

    /**
     * Determine whether this instruction creates new nodes.
     * This implementation returns true.
     */

    public final boolean mayCreateNewNodes() {
        return true;
    }

    /**
     * Perform optimisation of an expression and its subexpressions. This is the third and final
     * phase of static optimization.
     * <p>This method is called after all references to functions and variables have been resolved
     * to the declaration of the function or variable, and after all type checking has been done.</p>
     *
     * @param visitor     an expression visitor
     * @param contextInfo the static type of "." at the point where this expression is invoked.
     *                    The parameter is set to null if it is known statically that the context item will be undefined.
     *                    If the type of the context item is not known statically, the argument is set to
     *                    {@link Type#ITEM_TYPE}
     * @return the original expression, rewritten if appropriate to optimize execution
     * @throws XPathException if an error is discovered during this phase
     *                        (typically a type error)
     */
    @Override
    public Expression optimize(ExpressionVisitor visitor, ContextItemStaticInfo contextInfo) throws XPathException {
        Expression e = super.optimize(visitor, contextInfo);
        if (e != this) {
            return e;
        }
//        if (visitor.isOptimizeForStreaming()) {
//            DocumentInstr doc = new DocumentInstr(false, null);
//            doc.setContentExpression(getSelect());
//            setSelect(doc);
//        }
        return this;
    }

    public TailCall processLeavingTail(XPathContext context) throws XPathException {
        XsltController controller = (XsltController) context.getController();
        if (isAssert && !controller.isAssertionsEnabled()) {
            return null;
        }
        Receiver emitter = controller.makeMessageReceiver();
        PipelineConfiguration pipe = controller.makePipelineConfiguration();
        pipe.setHostLanguage(Configuration.XSLT);
        pipe.setXPathContext(context);
        emitter.setPipelineConfiguration(pipe);

        String code;
        try {
            code = getErrorCode().evaluateAsString(context).toString();
        } catch (XPathException err) {
            // use the error code of the failure in place of the intended error code
            code = err.getErrorCodeQName().getEQName();
        }

        StructuredQName errorCode = null;
        try {
            errorCode = StructuredQName.fromLexicalQName(
                    code, false, true, getRetainedStaticContext());
        } catch (XPathException err) {
            // The spec says we fall back to XTMM9000
            errorCode = new StructuredQName("err", NamespaceConstant.ERR, "XTMM9000");
        }

        controller.incrementMessageCounter(errorCode);

        SequenceReceiver rec = new TreeReceiver(emitter);
        rec = new MessageAdapter(rec, errorCode.getEQName(), getLocation());

        Receiver saved = context.getReceiver();

        SerializationProperties props = new SerializationProperties();
        props.setProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        SerializerFactory sf = context.getConfiguration().getSerializerFactory();

        Receiver receiver = sf.getReceiver(rec, props, pipe);
        context.setReceiver(receiver);

        boolean abort = false;
        String term = Whitespace.trim(getTerminate().evaluateAsString(context));
        switch (term) {
            case "no":
            case "false":
            case "0":
                // no action
                break;
            case "yes":
            case "true":
            case "1":
                abort = true;
                break;
            default:
                XPathException e = new XPathException("The terminate attribute of xsl:message must be yes|no|true|false|1|0");
                e.setXPathContext(context);
                e.setErrorCode("XTDE0030");
                throw e;
        }


        rec.startDocument(abort ? ReceiverOptions.TERMINATE : 0);

        try {
            SequenceIterator iter = getSelect().iterate(context);
            Item item;
            while ((item = iter.next()) != null) {
                rec.append(item, getLocation(), ReceiverOptions.ALL_NAMESPACES);
            }
        } catch (XPathException e) {
            rec.append(new StringValue("Error " + e.getErrorCodeLocalPart() +
                                               " while evaluating xsl:message at line "
                                               + getLocation().getLineNumber() + " of " + getLocation().getSystemId() +
                                               ": " + e.getMessage()));
        }

        rec.endDocument();
        rec.close();
        context.setReceiver(saved);
        if (abort) {
            TerminationException te = new TerminationException(
                    "Processing terminated by " + StandardErrorListener.getInstructionName(this) +
                            " at line " + getLocation().getLineNumber() +
                            " in " + StandardErrorListener.abbreviatePath(getLocation().getSystemId()));
            te.setLocation(getLocation());
            te.setErrorCodeQName(errorCode);
            throw te;
        }
        return null;

    }

    /**
     * Diagnostic print of expression structure. The abstract expression tree
     * is written to the supplied output destination.
     */

    public void export(ExpressionPresenter out) throws XPathException {
        out.startElement("message", this);
        out.setChildRole("select");
        getSelect().export(out);
        out.setChildRole("terminate");
        getTerminate().export(out);
        out.setChildRole("error");
        getErrorCode().export(out);
        out.endElement();
    }


    /**
     * The MessageAdapter is a filter applied to the message pipeline which is designed to ensure that outputting an attribute
     * with no containing element (for example &lt;xsl:message select="@x"/>) is not an error. Such an attribute is wrapped in
     * a processing instruction so it can exist as a child of a document node.
     * <p>
     * The MessageAdapter also inserts (as the first event after startDocument) a processing instruction
     * containing the error code, in the form &lt;?error-code Q{uri}local?>; the Location object passed
     * with this processing instruction represents the stylesheet location of the xsl:message instruction.
     */

    private static class MessageAdapter extends ProxyReceiver {
        private boolean contentStarted = true;
        private String errorCode;
        private Location location;

        public MessageAdapter(SequenceReceiver next, String errorCode, Location location) {
            super(next);
            this.errorCode = errorCode;
            this.location = location;
        }

        @Override
        public void startDocument(int properties) throws XPathException {
            super.startDocument(properties);
            processingInstruction("error-code", errorCode, location, 0);
        }

        @Override
        public void startElement(NodeName nameCode, SchemaType typeCode, Location location, int properties) throws XPathException {
            contentStarted = false;
            super.startElement(nameCode, typeCode, location, properties);
        }

        @Override
        public void startContent() throws XPathException {
            contentStarted = true;
            super.startContent();
        }

        @Override
        public void attribute(NodeName attributeName, SimpleType typeCode, CharSequence value, Location locationId, int properties)
                throws XPathException {
            if (contentStarted) {
                String attName = attributeName.getDisplayName();
                processingInstruction("attribute", "name=\"" + attName + "\" value=\"" + value + "\"", locationId, 0);
            } else {
                super.attribute(attributeName, typeCode, value, locationId, properties);
            }
        }

        @Override
        public void namespace(NamespaceBindingSet namespaceBindings, int properties) throws XPathException {
            if (contentStarted) {
                for (NamespaceBinding ns : namespaceBindings) {
                    String prefix = ns.getPrefix();
                    String uri = ns.getURI();
                    processingInstruction("namespace", "prefix=\"" + prefix + "\" uri=\"" + uri + "\"", ExplicitLocation.UNKNOWN_LOCATION, 0);
                }
            } else {
                super.namespace(namespaceBindings, properties);
            }
        }

        @Override
        public void append(Item item, Location locationId, int copyNamespaces) throws XPathException {
            if (item instanceof NodeInfo) {
                int kind = ((NodeInfo) item).getNodeKind();
                if (kind == Type.ATTRIBUTE || kind == Type.NAMESPACE) {
                    ((NodeInfo) item).copy(this, 0, locationId);
                    return;
                }
            } else if (item instanceof Function && !((Function) item).isArray()) {
                CharSequence representation = ((Function) item).isMap() ? Err.depict(item) : "Function " + Err.depict(item);
                nextReceiver.characters(representation, locationId, 0);
                return;
            }
            nextReceiver.append(item, locationId, copyNamespaces);
        }
    }

}