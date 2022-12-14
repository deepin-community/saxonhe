////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.style;

import com.saxonica.ee.stream.PostureAndSweep;
import com.saxonica.ee.stream.Streamability;
import com.saxonica.ee.stream.Sweep;
import com.saxonica.ee.trans.ContextItemStaticInfoEE;
import net.sf.saxon.Configuration;
import net.sf.saxon.expr.Component;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.StringLiteral;
import net.sf.saxon.expr.accum.Accumulator;
import net.sf.saxon.expr.accum.AccumulatorRegistry;
import net.sf.saxon.expr.accum.AccumulatorRule;
import net.sf.saxon.expr.instruct.Actor;
import net.sf.saxon.expr.instruct.SlotManager;
import net.sf.saxon.expr.parser.ExpressionTool;
import net.sf.saxon.expr.parser.ExpressionVisitor;
import net.sf.saxon.expr.parser.Optimizer;
import net.sf.saxon.expr.parser.RoleDiagnostic;
import net.sf.saxon.functions.AccumulatorFn;
import net.sf.saxon.lib.Feature;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.om.*;
import net.sf.saxon.pattern.NodeTest;
import net.sf.saxon.pattern.Pattern;
import net.sf.saxon.trace.LocationKind;
import net.sf.saxon.trans.*;
import net.sf.saxon.tree.iter.AxisIterator;
import net.sf.saxon.type.AtomicType;
import net.sf.saxon.type.ItemType;
import net.sf.saxon.type.UType;
import net.sf.saxon.value.SequenceType;
import net.sf.saxon.value.StringValue;
import net.sf.saxon.value.Whitespace;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler for xsl:accumulator elements in a stylesheet (XSLT 3.0).
 */

public class XSLAccumulator extends StyleElement implements StylesheetComponent {

    private Accumulator accumulator = new Accumulator();
    private boolean streamable;

    /**
     * Get the corresponding Procedure object that results from the compilation of this
     * StylesheetProcedure
     */
    public Actor getActor() {
        if (accumulator.getDeclaringComponent() == null) {
            accumulator.makeDeclaringComponent(Visibility.PRIVATE, getContainingPackage());
        }
        return accumulator;
    }

    public SymbolicName getSymbolicName() {
        StructuredQName qname = accumulator.getAccumulatorName();
        return qname==null ? null : new SymbolicName(StandardNames.XSL_ACCUMULATOR, null);
    }

    public void checkCompatibility(Component component) throws XPathException {
        // no action: accumulators cannot be overridden
    }

    /**
     * Ask whether this node is a declaration, that is, a permitted child of xsl:stylesheet
     * (including xsl:include and xsl:import).
     *
     * @return true for this element
     */

    @Override
    public boolean isDeclaration() {
        return true;
    }

    /**
     * Method to handle the name attributes, which may need to be evaluated early if there are forwards
     * references to this accumulator
     *
     * @throws XPathException if any error is detected
     */

    public void prepareSimpleAttributes() throws XPathException {

        AttributeCollection atts = getAttributeList();
        for (int a = 0; a < atts.getLength(); a++) {
            String f = atts.getQName(a);
            if (f.equals("name")) {
                String name = Whitespace.trim(atts.getValue(a));
                try {
                    accumulator.setAccumulatorName(makeQName(name));
                } catch (XPathException err) {
                    compileError("Accumulator name " + Err.wrap(name) + " is not a valid QName",
                            err.getErrorCodeQName());
                    accumulator.setAccumulatorName(new StructuredQName("saxon", NamespaceConstant.SAXON, "error-name"));
                }

            } else if (f.equals("streamable")) {
                accumulator.setDeclaredStreamable(false);
                streamable = processStreamableAtt(atts.getValue(a));
                accumulator.setDeclaredStreamable(streamable);
            } else if (atts.getURI(a).equals(NamespaceConstant.SAXON) && atts.getLocalName(a).equals("trace")) {
                accumulator.setTracing(processBooleanAttribute("saxon:trace", atts.getValue(a)));
            } else {
                // report the error later
            }
        }

        if (accumulator.getAccumulatorName() == null) {
            reportAbsence("name");
        }

    }

    public void prepareAttributes() throws XPathException {

        //prepareSimpleAttributes();

        AttributeCollection atts = getAttributeList();
        for (int a = 0; a < atts.getLength(); a++) {
            String f = atts.getQName(a);
            if (f.equals("name")) {
                // no action
            } else if (f.equals("streamable")) {
                // no action
            } else if (f.equals("initial-value")) {
                String exp = atts.getValue(a);
                accumulator.setInitialValueExpression(makeExpression(exp, a));
            } else if (f.equals("as")) {
                String asAtt = atts.getValue(a);
                try {
                    SequenceType requiredType = makeSequenceType(asAtt);
                    accumulator.setType(requiredType);
                } catch (XPathException e) {
                    compileErrorInAttribute(e.getMessage(), e.getErrorCodeLocalPart(), "as");
                }
            } else if (atts.getURI(a).equals(NamespaceConstant.SAXON) && atts.getLocalName(a).equals("trace")) {
                accumulator.setTracing(processBooleanAttribute("saxon:trace", atts.getValue(a)));
            } else {
                checkUnknownAttribute(atts.getNodeName(a));
            }
            // TODO: add saxon:as
        }

        if (accumulator.getType() == null) {
            accumulator.setType(SequenceType.ANY_SEQUENCE);
        }

        if (accumulator.getInitialValueExpression() == null) {
            reportAbsence("initial-value");
            StringLiteral zls = new StringLiteral(StringValue.EMPTY_STRING);
            zls.setRetainedStaticContext(makeRetainedStaticContext());
            accumulator.setInitialValueExpression(zls);
        }

    }

    @Override
    public void compileDeclaration(Compilation compilation, ComponentDeclaration decl) throws XPathException {

        Configuration config = compilation.getConfiguration();

        // Prepare the initial value expression

        {
            accumulator.setPackageData(compilation.getPackageData());
            accumulator.obtainDeclaringComponent(decl.getSourceElement());
            Expression init = accumulator.getInitialValueExpression();
            ExpressionVisitor visitor = ExpressionVisitor.make(getStaticContext());
            init = init.typeCheck(visitor, config.getDefaultContextItemStaticInfo());
            RoleDiagnostic role = new RoleDiagnostic(RoleDiagnostic.INSTRUCTION, "xsl:accumulator-rule/select", 0);
            init = config.getTypeChecker(false).staticTypeCheck(init, accumulator.getType(), role, visitor);
            init = init.optimize(visitor, config.getDefaultContextItemStaticInfo());
            SlotManager stackFrameMap = config.makeSlotManager();
            ExpressionTool.allocateSlots(init, 0, stackFrameMap);
            accumulator.setSlotManagerForInitialValueExpression(stackFrameMap);
            checkInitialStreamability(init);
            accumulator.addChildExpression(init);
        }

        // Prepare the new-value (select) expressions

        AxisIterator kids = iterateAxis(AxisInfo.CHILD);
        NodeInfo curr;
        while ((curr = kids.next()) != null) {
            if (curr instanceof XSLAccumulatorRule) {
                XSLAccumulatorRule rule = (XSLAccumulatorRule) curr;
                Pattern pattern = rule.getMatch();
                Expression newValueExp = rule.getNewValueExpression(compilation, decl);
                ExpressionVisitor visitor = ExpressionVisitor.make(getStaticContext());
                newValueExp = newValueExp.typeCheck(visitor, config.makeContextItemStaticInfo(pattern.getItemType(), false));
                RoleDiagnostic role = new RoleDiagnostic(RoleDiagnostic.INSTRUCTION, "xsl:accumulator-rule/select", 0);
                newValueExp = config.getTypeChecker(false).staticTypeCheck(newValueExp, accumulator.getType(), role, visitor);
                newValueExp = newValueExp.optimize(visitor, getConfiguration().makeContextItemStaticInfo(pattern.getItemType(), false));
                SlotManager stackFrameMap = getConfiguration().makeSlotManager();
                stackFrameMap.allocateSlotNumber(new StructuredQName("", "", "value"));
                ExpressionTool.allocateSlots(newValueExp, 1, stackFrameMap);
                boolean isPreDescent = !rule.isPostDescent();
                SimpleMode mode = isPreDescent ? accumulator.getPreDescentRules() : accumulator.getPostDescentRules();
                AccumulatorRule action = new AccumulatorRule(newValueExp, stackFrameMap, rule.isPostDescent());
                mode.addRule(pattern, action, decl.getModule(), decl.getModule().getPrecedence(), 1, 0, 0);

                checkRuleStreamability(rule, pattern, newValueExp);

                if (accumulator.isDeclaredStreamable() && rule.isPostDescent() && rule.isCapture()) {
                    action.setCapturing(true);
                }

                ItemType itemType = pattern.getItemType();
                if (itemType instanceof NodeTest) {
                    if (!itemType.getUType().overlaps(UType.DOCUMENT.union(UType.CHILD_NODE_KINDS))) {
                        rule.compileWarning("An accumulator rule that matches attribute or namespace nodes has no effect", "SXWN9999");
                    }
                } else if (itemType instanceof AtomicType) {
                    rule.compileWarning("An accumulator rule that matches atomic values has no effect", "SXWN9999");
                }

                accumulator.addChildExpression(newValueExp);
                accumulator.addChildExpression(pattern);
            }
        }

        accumulator.getPreDescentRules().allocateAllPatternSlots();
        accumulator.getPostDescentRules().allocateAllPatternSlots();
    }

    /**
     * Get a name identifying the object of the expression, for example a function name, template name,
     * variable name, key name, element name, etc. This is used only where the name is known statically.
     * If there is no name, the value will be -1.
     */

    /*@NotNull*/
    public StructuredQName getObjectName() {
        StructuredQName qn = super.getObjectName();
        if (qn == null) {
            String nameAtt = Whitespace.trim(getAttributeValue("", "name"));
            if (nameAtt == null) {
                return new StructuredQName("saxon", NamespaceConstant.SAXON, "badly-named-accumulator" + generateId());
            }
            try {
                qn = makeQName(nameAtt);
                setObjectName(qn);
            } catch (XPathException err) {
                return new StructuredQName("saxon", NamespaceConstant.SAXON, "badly-named-accumulator" + generateId());
            }
        }
        return qn;
    }


    public void index(ComponentDeclaration decl, PrincipalStylesheetModule top) throws XPathException {
        if (accumulator.getAccumulatorName() == null) {
            prepareSimpleAttributes();
        }
        accumulator.setImportPrecedence(decl.getPrecedence());
        if (top.getAccumulatorManager() == null) {
            StyleNodeFactory styleNodeFactory = getCompilation().getStyleNodeFactory(true);
            AccumulatorRegistry manager = styleNodeFactory.makeAccumulatorManager();
            top.setAccumulatorManager(manager);
            getCompilation().getPackageData().setAccumulatorRegistry(manager);
        }
        AccumulatorRegistry mgr = top.getAccumulatorManager();
        Accumulator existing = mgr.getAccumulator(accumulator.getAccumulatorName());
        if (existing != null) {
            int existingPrec = existing.getImportPrecedence();
            if (existingPrec == decl.getPrecedence()) {
                compileError("There are two accumulators with the same name (" +
                        accumulator.getAccumulatorName().getDisplayName() + ") and the same import precedence", "XTSE3350");
            }
            if (existingPrec > decl.getPrecedence()) {
                return;
            }
        }
        mgr.addAccumulator(accumulator);
    }

    public void validate(ComponentDeclaration decl) throws XPathException {

        //stackFrameMap = getConfiguration().makeSlotManager();

        // check the element is at the top level of the stylesheet

        checkTopLevel("XTSE0010", true);

        // only permitted child is XSLAccumulatorRule, and there must be at least one

        boolean foundRule = false;
        AxisIterator kids = iterateAxis(AxisInfo.CHILD);
        NodeInfo curr;
        while ((curr = kids.next()) != null) {
            if (curr instanceof XSLAccumulatorRule) {
                foundRule = true;
            } else {
                compileError("Only xsl:accumulator-rule is allowed here", "XTSE0010");
            }
        }

        if (!foundRule) {
            compileError("xsl:accumulator must contain at least one xsl:accumulator-rule", "XTSE0010");
        }

    }

    public SlotManager getSlotManager() {
        return null;
    }

    public void optimize(ComponentDeclaration declaration) throws XPathException {
        // no action
    }


    /**
     * Get the type of value returned by this function
     *
     * @return the declared result type, or the inferred result type
     *         if this is more precise
     */
    public SequenceType getResultType() {
        return accumulator.getType();
    }


    /**
     * Get the type of construct. This will be a constant in
     * class {@link LocationKind}. This method is part of the
     * {@link net.sf.saxon.trace.InstructionInfo} interface
     */

    public int getConstructType() {
        return StandardNames.XSL_ACCUMULATOR;
    }

    /**
     * Generate byte code if appropriate
     * @param opt the optimizer
     */
    public void generateByteCode(Optimizer opt) {
        // no action currently
    }

    private void checkInitialStreamability(Expression init) throws XPathException {
        // Check streamability constraints
        //#ifdefined STREAM
        if (accumulator.isDeclaredStreamable()) {
            PostureAndSweep ps = Streamability.getStreamability(init, ContextItemStaticInfoEE.DEFAULT, null);
            if (ps.getSweep() != Sweep.MOTIONLESS) {
                notStreamable(this,"The initial value expression for a streaming accumulator must be motionless");
            }
        }

        //#endif
    }

    private void checkRuleStreamability(XSLAccumulatorRule rule, Pattern pattern, Expression newValueExp) throws XPathException {
        // Check streamability constraints
        //#ifdefined STREAM
        if (accumulator.isDeclaredStreamable()) {
            boolean fallback = getConfiguration().getBooleanProperty(Feature.STREAMING_FALLBACK);
            if (!pattern.isMotionless()) {
                String message = "The patterns for the accumulator rules in a streaming accumulator must be motionless";
                notStreamable(rule, message);
            }
            ContextItemStaticInfoEE csi = (ContextItemStaticInfoEE)getConfiguration().makeContextItemStaticInfo(pattern.getItemType(), false);
            csi.setContextPostureStriding();
            csi.setAccumulatorPhase(rule.isPostDescent() ? AccumulatorFn.Phase.AFTER : AccumulatorFn.Phase.BEFORE);
            List<String> reasons = new ArrayList<>(4);
            PostureAndSweep ps = Streamability.getStreamability(newValueExp, csi, reasons);
            if (!ps.equals(PostureAndSweep.GROUNDED_AND_MOTIONLESS) && !rule.isCapture()) {
                // saxon:capture="yes" captures a snapshot of the element for access in the phase="end" rule, which
                // therefore no longer needs to be motionless
                StringBuilder message = new StringBuilder("The xsl:accumulator-rule/@select expression (or contained sequence constructor) " +
                                                                  "for a streaming accumulator must be grounded and motionless");
                for (String reason : reasons) {
                    message.append(". ").append(reason);
                }
                notStreamable(rule, message.toString());
            }
        }
        //#endif
    }

    private void notStreamable(StyleElement rule, String message) throws XPathException {
        boolean fallback = getConfiguration().getBooleanProperty(Feature.STREAMING_FALLBACK);
        if (fallback) {
            message += ". Falling back to non-streaming implementation";
            rule.compileWarning(message, "XTSE3430");
            rule.getCompilation().setFallbackToNonStreaming(true);
        } else {
            rule.compileError(message, "XTSE3430");
        }
    }

}
