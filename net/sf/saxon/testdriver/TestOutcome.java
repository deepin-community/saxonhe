////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.testdriver;

import net.sf.saxon.expr.ItemMappingFunction;
import net.sf.saxon.expr.ItemMappingIterator;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.expr.sort.AtomicMatchKey;
import net.sf.saxon.expr.sort.AtomicSortComparer;
import net.sf.saxon.expr.sort.CodepointCollator;
import net.sf.saxon.expr.sort.GenericAtomicComparer;
import net.sf.saxon.functions.DeepEqual;
import net.sf.saxon.lib.Feature;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.lib.StringCollator;
import net.sf.saxon.om.*;
import net.sf.saxon.regex.RegularExpression;
import net.sf.saxon.s9api.*;
import net.sf.saxon.trans.NoDynamicContextException;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.util.FastStringBuffer;
import net.sf.saxon.type.Type;
import net.sf.saxon.value.AtomicValue;
import net.sf.saxon.value.StringValue;
import net.sf.saxon.value.Whitespace;

import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class TestOutcome {
    /**
     * The outcome of a test is either an XDM value or an exception. The object also has the ability to
     * hold the serialized result (needed for serialization tests)
     */

    public static class SingleResultDoc {
        public XdmValue value;
        public String serialization;
        public boolean wellFormed = true;

        public SingleResultDoc() {
        }

        public SingleResultDoc(XdmValue value, String serialization) {
            this.value = value;
            this.serialization = serialization;
        }
    }

    private TestDriver driver;
    private SingleResultDoc principalResult = new SingleResultDoc();
    private Set<String> errorsReported;
    private boolean warningsReported;
    private SaxonApiException exception;
    private String comment;
    private Set<XdmNode> xslMessages = new HashSet<>(4);
    private Map<URI, SingleResultDoc> xslResultDocuments = new HashMap<>(4);
    private String wrongError;
    private String resultVar;
    private String baseOutputUri;

    public TestOutcome(TestDriver driver) {
        this.driver = driver;
    }

    public void setException(SaxonApiException exception) {
        this.exception = exception;
    }

    public boolean isException() {
        return exception != null;
    }

    public void setResultVar(String variableName) {
        resultVar = variableName;
    }

    public void setBaseOutputUri(String uri) {
        baseOutputUri = uri;
    }

    public String getBaseOutputUri() {
        return baseOutputUri;
    }

    public SaxonApiException getException() {
        return exception;
    }

    /**
     * Get a message giving details about the situation where the actual error code was not one of those
     * expected
     *
     * @return null if this situation did not occur; otherwise a message showing the actual error code
     * and the expected error code
     */

    public String getWrongErrorMessage() {
        return wrongError;
    }


    public void setPrincipalResult(XdmValue value) {
        principalResult.value = value;
    }

    public void setWarningsReported(boolean warnings) {
        this.warningsReported = warnings;
    }

    public boolean isWarningsReported() {
        return this.warningsReported;
    }

    public XdmValue getPrincipalResult() {
        return principalResult.value;
    }

    public SingleResultDoc getPrincipalResultDoc() {
        return principalResult;
    }

    public synchronized void setSecondaryResult(URI uri, XdmValue value, String serialization) {
        SingleResultDoc result = xslResultDocuments.get(uri);
        if (result == null) {
            result = new SingleResultDoc(value, serialization);
            this.xslResultDocuments.put(uri, result);
        } else {
            if (value != null) {
                result.value = value;
            }
            if (serialization != null) {
                result.serialization = serialization;
            }
        }
    }

    public synchronized SingleResultDoc getSecondaryResult(URI uri) {
        return xslResultDocuments.get(uri);
    }

    public Map<URI, SingleResultDoc> getSecondaryResultDocuments() {
        return xslResultDocuments;
    }

    public void setErrorsReported(Set<String> errors) {
        errorsReported = errors;
    }

    public synchronized void addReportedError(String error) {
        if (errorsReported == null) {
            errorsReported = new HashSet<>();
        }
        errorsReported.add(error);
    }

    public synchronized boolean hasReportedError(String errorCode) {
        return errorsReported != null && errorsReported.contains(errorCode);
    }

    public String toString() {
        return isException() ? "EXCEPTION " + exception.getMessage() : getPrincipalResult().toString();
    }

    public void setPrincipalSerializedResult(String result) {
        principalResult.serialization = result;
    }

    public String getPrincipalSerializedResult() {
        return principalResult.serialization;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    public synchronized void addXslMessage(XdmNode message) {
        xslMessages.add(message);
    }


    /**
     * This method serializes the actual result to produce a serialized result. This is not what is actually
     * needed by the serialization tests, which require that the serialization be performed using the parameters
     * contained within the query itself.
     *
     * @param p   the processor
     * @param uri the URI of the result to serialize; null for the principal result
     * @return the result of serialization
     */

    public String serialize(Processor p, URI uri) {
        XdmValue value;
        if (uri == null) {
            if (principalResult.serialization != null) {
                return principalResult.serialization;
            } else {
                value = principalResult.value;
            }
        } else {
            SingleResultDoc doc = xslResultDocuments.get(uri);
            if (doc == null) {
                return "[[[NULL VALUE]]]";
            } else if (doc.serialization != null) {
                return doc.serialization;
            } else {
                value = doc.value;
            }
        }
        if (value == null) {
            return "[[[NULL VALUE]]]";
        }
        if (isException()) {
            return "EXCEPTION " + exception.getMessage();
        } else {
            StringWriter sw = new StringWriter();
            Serializer s = p.newSerializer(sw);
            s.setOutputProperty(Serializer.Property.METHOD, "xml");
            s.setOutputProperty(Serializer.Property.INDENT, "no");
            s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
            try {
                s.serializeXdmValue(value);
            } catch (SaxonApiException err) {
                return "SERIALIZATION FAILED: " + err.getMessage();
            }
            String str = sw.toString().trim();
            if (uri == null) {
                principalResult.serialization = str;
            } else {
                xslResultDocuments.get(uri).serialization = str;
            }
            return str;
        }
    }

    /**
     * This method serializes the actual result to produce a serialized result. This is not what is actually
     * needed by the serialization tests, which require that the serialization be performed using the parameters
     * contained within the query itself.
     *
     * @param p   the processor
     * @param doc the result to serialize
     * @return the result of serialization
     */

    public String serialize(Processor p, SingleResultDoc doc) {
        XdmValue value;
        if (doc.serialization != null) {
            return doc.serialization;
        } else {
            value = doc.value;
        }
        if (value == null) {
            return "[[[NULL VALUE]]]";
        }
        if (isException()) {
            return "EXCEPTION " + exception.getMessage();
        } else {
            StringWriter sw = new StringWriter();
            Serializer s = p.newSerializer(sw);
            s.setOutputProperty(Serializer.Property.METHOD, "adaptive");
            s.setOutputProperty(Serializer.Property.INDENT, "no");
            s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
            try {
                s.serializeXdmValue(value);
            } catch (SaxonApiException err) {
                return "SERIALIZATION FAILED: " + err.getMessage();
            }
            String str = sw.toString().trim();
            doc.serialization = str;
            return str;
        }
    }

    public String serialize(Processor p, XdmValue value) {
        StringWriter sw = new StringWriter();
        Serializer s = p.newSerializer(sw);
        s.setOutputProperty(Serializer.Property.METHOD, "xml");
        s.setOutputProperty(Serializer.Property.INDENT, "no");
        s.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
        try {
            s.serializeXdmValue(value);
        } catch (SaxonApiException err) {
            return "SERIALIZATION FAILED: " + err.getMessage();
        }
        return sw.toString().trim();
    }

    public boolean testAssertion(XdmNode assertion, TestOutcome outcome, SingleResultDoc result, XPathCompiler assertXpc, XPathCompiler catalogXpc, boolean debug) {
        try {
            String tag = assertion.getNodeName().getLocalName();
            boolean success = testAssertion2(assertion, outcome, result, assertXpc, catalogXpc, debug);
            if (debug && !"all-of".equals(tag) && !"any-of".equals(tag) && !"not".equals(tag)) {
                String parentTag = assertion.getParent().getNodeName().getLocalName();
                String label = "Assertion " + tag;
                if (parentTag.equals("not") || parentTag.equals("any-of") || parentTag.equals("all-of")) {
                    label = "(Within " + parentTag + ") " + label;
                }
                driver.println(label + " (" + assertion.getStringValue() + ") " + (success ? " succeeded" : " failed"));
                if (tag.equals("error")) {
                    if (isException()) {
                        boolean b = compareExpectedError(assertion);
                        if (b) {
                            driver.println("Returned error as expected");
                        } else {
                            driver.println(wrongError);
                        }
                    } else {
                        driver.println("Expected error " + assertion.attribute("code") + "; got success");
                    }
                } else if (!success && isException()) {
                    FastStringBuffer fsb = new FastStringBuffer(32);
                    fsb.append("Expected success, got error ");
                    if (errorsReported != null) {
                        for (String e : errorsReported) {
                            fsb.append(e);
                            fsb.append("|");
                        }
                    }
                    fsb.setLength(fsb.length() - 1);
                    driver.println(fsb.toString());
                }
            }
            if (!success && wrongError != null) {
                // at this stage getting the wrong error means failure (or at least, !success),
                // the test drivers later pick up a wrong error and treat it as a pass
                success = false;
            }
            return success;
        } catch (SaxonApiException e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean testAssertion2(XdmNode assertion, TestOutcome outcome, SingleResultDoc result, XPathCompiler assertXpc, XPathCompiler catalogXpc, boolean debug) throws SaxonApiException {
        String tag = assertion.getNodeName().getLocalName();

        switch (tag) {
            case "assert-eq":
                return assertEq(assertion, result, assertXpc);

            case "assert-deep-eq":
                return assertDeepEq(assertion, result, assertXpc);

            case "assert-permutation":
                return assertPermutation(assertion, result, assertXpc);

            case "assert-xml":
                return assertXml(assertion, result, assertXpc, catalogXpc, debug);

            case "serialization-matches":
                return assertSerializationMatches(assertion, result, catalogXpc);

            case "assert-serialization-error":
                return assertSerializationError(assertion, result, assertXpc);

            case "assert-empty":
                return assertEmpty(result.value);

            case "assert-count":
                return assertCount(assertion, result);

            case "assert":
                return assertXPath(assertion, result, assertXpc, debug);

            case "assert-string-value":
                return assertStringValue(assertion, result, debug);

            case "assert-serialization":
                return assertSerialization(assertion, result, catalogXpc, debug);

            case "assert-type":
                return assertType(assertion, result, assertXpc);

            case "assert-true":
                return assertTrue(result);

            case "assert-false":
                return assertFalse(result);
            case "assert-warning":
                return assertWarning();

            case "assert-message": {
                XdmNode subAssertion = (XdmNode) catalogXpc.evaluateSingle("*", assertion);
                for (XdmNode message : xslMessages) {
                    if (message.getNodeKind() == XdmNodeKind.PROCESSING_INSTRUCTION && message.getNodeName().getLocalName().equals("trust") && message.getStringValue().equals("me")) {
                        // In the JS tests, assume message assertions are OK
                        return true;
                    }
                    if (testAssertion2(subAssertion, outcome, new SingleResultDoc(message, ""), assertXpc, catalogXpc, debug)) {
                        return true;
                    }
                }
                return false;

            }
            case "assert-result-document": {
                XdmNode subAssertion = (XdmNode) catalogXpc.evaluateSingle("*", assertion);
                //URI uri = new File(driver.resultsDir + "/results/output.xml").toURI().resolve(assertion.attribute("uri"));
                URI uri;
                try {
                    uri = new URI(outcome.getBaseOutputUri()).resolve(assertion.attribute("uri"));
                } catch (URISyntaxException e) {
                    System.err.println("**** Invalid output uri " + outcome.getBaseOutputUri());
                    return false;
                }
                SingleResultDoc doc = getSecondaryResult(uri);
                if (doc == null) {
                    System.err.println("**** No output document found for " + uri);
                    return false;
                }
                boolean ok = testAssertion2(subAssertion, outcome, doc, assertXpc, catalogXpc, debug);
                if (!ok) {
                    System.err.println("**** Assertion failed for result-document " + uri);
                }
                return ok;

            }
            case "error":
                return isException() && compareExpectedError(assertion);

            case "all-of":
                for (XdmNode child : assertion.children("*")) {
                    if (!testAssertion((XdmNode) child, outcome, result, assertXpc, catalogXpc, debug)) {
                        return false;
                    }
                }
                return true;

            case "any-of":
                boolean partialSuccess = false;
                for (XdmItem child : catalogXpc.evaluate("*", assertion)) {
                    if (testAssertion((XdmNode) child, outcome, result, assertXpc, catalogXpc, debug)) {
                        if (wrongError != null) {
                            partialSuccess = true;
                            continue;
                        }
                        return true;
                    }
                }
                return partialSuccess;

            case "not": {
                XdmNode subAssertion = (XdmNode) catalogXpc.evaluateSingle("*", assertion);
                return !testAssertion(subAssertion, outcome, result, assertXpc, catalogXpc, debug);
            }
        }
        throw new IllegalStateException("Unknown assertion element " + tag);
    }

    private boolean assertFalse(SingleResultDoc result) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            return result.value.size() == 1 &&
                    result.value.itemAt(0).isAtomicValue() &&
                    ((XdmAtomicValue) result.value.itemAt(0)).getPrimitiveTypeName().equals(QName.XS_BOOLEAN) &&
                    !((XdmAtomicValue) result.value.itemAt(0)).getBooleanValue();
        }
    }

    private boolean assertTrue(SingleResultDoc result) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            return result.value.size() == 1 &&
                    result.value.itemAt(0).isAtomicValue() &&
                    ((XdmAtomicValue) result.value.itemAt(0)).getPrimitiveTypeName().equals(QName.XS_BOOLEAN) &&
                    ((XdmAtomicValue) result.value.itemAt(0)).getBooleanValue();
        }
    }

    private boolean assertWarning() {
        return isWarningsReported();
    }

    private boolean assertType(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            assertXpc.declareVariable(new QName("result"));
            XPathSelector s = assertXpc.compile("$result instance of " + assertion.getStringValue()).load();
            s.setVariable(new QName("result"), result.value);
            return ((XdmAtomicValue) s.evaluateSingle()).getBooleanValue();
        }
    }

    private boolean assertStringValue(XdmNode assertion, SingleResultDoc result, boolean debug) {
        if (isException()) {
            return false;
        } else {
            String resultString;
            String assertionString = assertion.getStringValue();
            if (result.value instanceof XdmItem) {
                resultString = ((XdmItem) result.value).getStringValue();
            } else {
                boolean first = true;
                FastStringBuffer fsb = new FastStringBuffer(256);
                for (XdmItem item : result.value) {
                    if (first) {
                        first = false;
                    } else {
                        fsb.append(' ');
                    }
                    fsb.append(item.getStringValue());
                }
                resultString = fsb.toString();
            }
            String normalizeAtt = assertion.attribute("normalize-space");
            if (normalizeAtt == null && driver.catalogNamespace().equals("http://www.w3.org/2012/10/xslt-test-catalog")) {
                // default in XSLT test suite is "true"
                normalizeAtt = "true";
            }
            if (normalizeAtt != null && (normalizeAtt.trim().equals("true") || normalizeAtt.trim().equals("1"))) {
                assertionString = Whitespace.collapseWhitespace(assertionString).toString();
                resultString = Whitespace.collapseWhitespace(resultString).toString();
            }
            if (resultString.equals(assertionString)) {
                return true;
            } else {
                if (debug) {
                    if (resultString.length() != assertionString.length()) {
                        driver.println("Result length " + resultString.length() + "; expected length " + assertionString.length());
                    }
                    int len = Math.min(resultString.length(), assertionString.length());
                    for (int i = 0; i < len; i++) {
                        if (resultString.charAt(i) != assertionString.charAt(i)) {
                            driver.println("Actual:'" + StringValue.diagnosticDisplay(resultString) + "'");
                            driver.println("Results differ at index " + i +
                                "(\"" + StringValue.diagnosticDisplay(resultString.substring(i, i + 10 > len ? len : i + 10)) + "\") vs (\"" +
                                StringValue.diagnosticDisplay(assertionString.substring(i, i + 10 > len ? len : i + 10)) + "\")");
                            break;
                        }
                    }
                }
                return false;
            }
        }
    }

    private boolean assertSerialization(XdmNode assertion, SingleResultDoc result, XPathCompiler xpath, boolean debug) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            String method = assertion.attribute("method");
            if (method == null) {
                method = "xml";
            }
            String resultString = result.serialization;
            String comparand = xpath.evaluate(
                    "if (@file) then " +
                            "if (@encoding) " +
                            "then unparsed-text(resolve-uri(@file, base-uri(.)), @encoding) " +
                            "else unparsed-text(resolve-uri(@file, base-uri(.))) " +
                            "else string(.)", assertion).toString();
            comparand = comparand.replace("\r\n", "\n");
            if (comparand.endsWith("\n")) {
                comparand = comparand.substring(0, comparand.length() - 1);
            }

            if (resultString == null) {
                if (result.value instanceof XdmItem) {
                    resultString = ((XdmItem) result.value).getStringValue();
                } else {
                    if (debug) {
                        driver.println("Assert serialization fails: result is a sequence");
                    }
                    return false;
                }
            }
            boolean isHtml = method.equals("html") || method.equals("xhtml");
            boolean normalize = isHtml;
            if (!normalize) {
                String normalizeAtt = assertion.attribute("normalize-space");
                normalize = normalizeAtt != null && (normalizeAtt.trim().equals("true") || normalizeAtt.trim().equals("1"));
            }
            if (normalize) {
                comparand = Whitespace.collapseWhitespace(comparand).toString();
                resultString = Whitespace.collapseWhitespace(resultString).toString();
            } else if (resultString.endsWith("\n")) {
                resultString = resultString.substring(0, resultString.length() - 1);
            }
            if (isHtml) {
                // should really do this only for block-level elements
                comparand = comparand.replace(" <", "<");
                comparand = comparand.replace("> ", ">");
                resultString = resultString.replace(" <", "<");
                resultString = resultString.replace("> ", ">");
            }
            if (resultString.equals(comparand)) {
                return true;
            } else {
                if (debug) {
                    if (resultString.length() != comparand.length()) {
                        driver.println("Result length " + resultString.length() + "; expected length " + comparand.length());
                    }
                    int len = Math.min(resultString.length(), comparand.length());
                    for (int i = 0; i < len; i++) {
                        if (resultString.charAt(i) != comparand.charAt(i)) {
                            int start = i < 20 ? 0 : i-20;
                            int end = i + 20 > len ? len : i+20;
                            driver.println("Serialized results differ at index " + i +
                                "(\"" + StringValue.diagnosticDisplay(resultString.substring(start, end)) + "\"), expected (\"" +
                                StringValue.diagnosticDisplay(comparand.substring(start, end)) + "\")");
                            break;
                        }
                    }
                    driver.println("Actual results:");
                    driver.println(resultString);
                } else {
                    driver.println("Serialized results differ");
                }
                return false;
            }
        }
    }

    private boolean assertXPath(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc, boolean debug) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            XdmSequenceIterator iter = assertion.axisIterator(Axis.NAMESPACE);
            while (iter.hasNext()) {
                XdmNode namespace = (XdmNode) iter.next();
                if (namespace.getNodeName() != null) {
                    assertXpc.declareNamespace(namespace.getNodeName().getLocalName(), namespace.getStringValue());
                }
            }
            if (resultVar != null) {
                assertXpc.declareVariable(new QName("result"));
            }
            driver.println("Testing " + assertion.getStringValue());
            XPathExecutable exp = assertXpc.compile(assertion.getStringValue());
            XPathSelector s = exp.load();
            QName resultVarName = new QName("result");
            if (resultVar != null) {
                s.setVariable(new QName(resultVar), result.value);
            } else if (exp.getRequiredCardinalityForVariable(resultVarName) == null) {
                if (result.value instanceof XdmItem) { // this path used in XSLT tests
                    s.setContextItem((XdmItem) result.value);
                }
            } else {
                s.setVariable(resultVarName, result.value);
            }
            boolean b = s.effectiveBooleanValue();
            if (!b && debug) {
                driver.println("XPath assertion " + assertion.getStringValue() + " failed");
                try {
                    String ass = assertion.getStringValue();
                    // Try to evaluate the expression on the lhs of an "=" operator in the assertion
                    int eq = ass.indexOf("=");
                    if (eq > 0) {
                        ass = ass.substring(0, eq);
                        exp = assertXpc.compile(ass);
                        s = exp.load();
                        if (exp.getRequiredCardinalityForVariable(resultVarName) == null) {
                            if (result.value instanceof XdmItem) { // this path used in XSLT tests
                                s.setContextItem((XdmItem) result.value);
                            }
                        } else {
                            s.setVariable(resultVarName, result.value);
                        }
                        XdmValue val = s.evaluate();
                        driver.println("Actual result of " + ass + ": " + val.toString());
                    }
                } catch (Exception err) {
                    // Occurs for example with an assertion like /x[a = 2] where what precedes the '=' is not an expression
                }
                driver.println("Actual results: " + result.value);
            }
            return b;
        }
    }

    private boolean assertCount(XdmNode assertion, SingleResultDoc result) {
        if (isException()) {
            return false;
        } else {
            int expected = Integer.parseInt(assertion.getStringValue());
            int actual = result.value.size();
            if (actual != expected) {
                driver.println("Expected result size = " + expected + "; actual size = " + actual);
            }
            return actual == expected;
        }
    }

    private boolean assertEmpty(XdmValue result) {
        return !isException() && result.size() == 0;
    }

    private boolean assertSerializationError(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc) {
        if (isException()) {
            return compareExpectedError(assertion);
        } else {
            String expectedError = assertion.attribute("code");
            driver.println("Expected serialization error " + expectedError + "; got success");
            return false;
        }
//        } else if (result.value == null) {
//            driver.println("Expected " );
//            return false;
//        } else {
//            String expectedError = assertion.getAttributeValue(new QName("code"));
//            StringWriter sw = new StringWriter();
//            Serializer serializer = assertXpc.getProcessor().newSerializer(sw);
//            serializer.setOutputProperty(Serializer.Property.METHOD, "xml");
//            serializer.setOutputProperty(Serializer.Property.INDENT, "no");
//            serializer.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
//            try {
//                serializer.serializeXdmValue(result.value);
//                return false;
//            } catch (SaxonApiException err) {
//                boolean b = expectedError.equals(err.getErrorCode().getLocalName());
//                if (!b) {
//                    driver.println("Expected " + expectedError + ", got " + err.getErrorCode().getLocalName());
//                }
//                return true;
//            }
//        }
    }

    private boolean assertSerializationMatches(XdmNode assertion, SingleResultDoc result, XPathCompiler xpath) {
        if (isException()) {
            return false;
        } else {
            //String testSet = catalogXpc.evaluate("string(/*/@name)", assertion).toString();
            String flagsAtt = assertion.attribute("flags");
            if (flagsAtt == null) {
                flagsAtt = "";
            }
           /* String regex = xpath.evaluate(
                    "if (@file) then " +
                            "if (@encoding) " +
                            "then unparsed-text(resolve-uri(@file, base-uri(.)), @encoding) " +
                            "else unparsed-text(resolve-uri(@file, base-uri(.))) " +
                            "else string(.)", assertion).toString();
            regex = regex.replace("\r\n", "\n");
            if (regex.endsWith("\n")) {
                regex = regex.substring(0, regex.length() - 1);
            }*/
            String regex = assertion.getStringValue();
            List<String> warnings = new ArrayList<>(1);
            try {
                String principalSerializedResult = result.serialization;
                if (principalSerializedResult == null) {
                    driver.println("No serialized result available!");
                    return false;
                }
                RegularExpression re = xpath.getProcessor().getUnderlyingConfiguration().compileRegularExpression(
                        regex, flagsAtt, "XP30", warnings);
                if (re.containsMatch(principalSerializedResult)) {
                    return true;
                } else {
                    driver.println("Serialized result:");
                    driver.println(principalSerializedResult);
                    return false;
                }
            } catch (XPathException e) {
                throw new AssertionError(e);
            }
        }
    }

    private boolean assertXml(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc, XPathCompiler catalogXpc, boolean debug) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            String normalizeAtt = assertion.attribute("normalize-space");
            boolean normalize = normalizeAtt != null && ("true".equals(normalizeAtt.trim()) || "1".equals(normalizeAtt.trim()));
            String ignoreAtt = assertion.attribute("ignore-prefixes");
            boolean ignorePrefixes = ignoreAtt != null && ("true".equals(ignoreAtt.trim()) || "1".equals(ignoreAtt.trim()));
            String xmlVersion = assertion.attribute("xml-version");
            boolean xml11 = "1.1".equals(xmlVersion);

            String comparand = catalogXpc.evaluate("if (@file) then unparsed-text(resolve-uri(@file, base-uri(.))) else string(.)", assertion).toString();
            if (comparand.startsWith("<?xml")) {
                int index = comparand.indexOf("?>");
                comparand = comparand.substring(index + 2);
            }
           /* String expectedDoctype = null;
            if (comparand.startsWith("<!DOCTYPE")) {
                int index = comparand.indexOf(">");
                expectedDoctype = comparand.substring(0,index+1);
            }*/
            comparand = comparand.trim();
            comparand = comparand.replace("\r\n", "\n");
            if (normalize) {
                comparand = Whitespace.collapseWhitespace(comparand).toString();
            }

            if (comparand.equals(serialize(assertXpc.getProcessor(), result))) {
                return true;
            }

            DocumentBuilder builder = assertXpc.getProcessor().newDocumentBuilder();
            if (xml11) {
                assertXpc.getProcessor().setConfigurationProperty(Feature.XML_VERSION, "1.1");
            }
            StringReader reader = new StringReader((xml11 ? "<?xml version='1.1'?>" : "") + "<z>" + comparand + "</z>");
            /*if (expectedDoctype != null) {
                reader = new StringReader(comparand);
            }*/
            XdmNode expected = builder.build(new StreamSource(reader));

            int flag = 0;

            flag |= DeepEqual.INCLUDE_COMMENTS;
            flag |= DeepEqual.INCLUDE_PROCESSING_INSTRUCTIONS;
            flag |= DeepEqual.EXCLUDE_VARIETY;
            if (!ignorePrefixes) {
                flag |= DeepEqual.INCLUDE_NAMESPACES;
                flag |= DeepEqual.INCLUDE_PREFIXES;
            }
            flag |= DeepEqual.COMPARE_STRING_VALUES;
            if (debug) {
                flag |= DeepEqual.WARNING_IF_FALSE;
            }
            try {
                SequenceIterator<?> iter0;
                if (result == null) {
                    System.err.println("Result value is null");
                    return false;
                }
                XdmValue value = result.value;
                if (value == null) {
                    System.err.println("Result value is null (perhaps serialized?)");
                    return false;
                }
                if (value.size() == 1 && value.itemAt(0) instanceof XdmNode && ((XdmNode) value.itemAt(0)).getNodeKind() == XdmNodeKind.DOCUMENT) {
                    iter0 = ((XdmNode) value.itemAt(0)).getUnderlyingNode().iterateAxis(AxisInfo.CHILD);
                } else {
                    iter0 = value.getUnderlyingValue().iterate();
                }
                GroundedValue<?> val0 = iter0.materialize();
                SequenceIterator<?> iter1 = expected.axisIterator(Axis.CHILD).next()
                        .getUnderlyingValue().iterateAxis(AxisInfo.CHILD);
                GroundedValue<?> val1 = iter1.materialize();
                boolean success = DeepEqual.deepEqual(
                    val0.iterate(), val1.iterate(),
                    new GenericAtomicComparer(CodepointCollator.getInstance(), null),
                    assertXpc.getProcessor().getUnderlyingConfiguration().getConversionContext(), flag);
                // if necessary try again ignoring whitespace nodes
                if (!success) {
                    iter0 = val0.iterate();
                    iter1 = val1.iterate();
                    // deep-equals with the EXCLUDE_WHITESPACE flag doesn't ignore top-level whitespace, so we
                    // need to filter that out ourselves
                    iter0 = new ItemMappingIterator(iter0, new RemoveWhitespace());
                    iter1 = new ItemMappingIterator(iter1, new RemoveWhitespace());
                    success = DeepEqual.deepEqual(
                        iter0, iter1,
                        new GenericAtomicComparer(CodepointCollator.getInstance(), null),
                        assertXpc.getProcessor().getUnderlyingConfiguration().getConversionContext(),
                        flag | DeepEqual.EXCLUDE_WHITESPACE_TEXT_NODES);
                    if (success) {
                        comment = "OK after ignoring whitespace text";
                    }
                }
                if (!success) {
                    driver.println("assert-xml comparison failed");
                    if (debug) {
                        driver.println("assert-xml comparison failed");
                        driver.println("Reference results:");
                        /*if(expectedDoctype != null) {
                            System.err.println(expectedDoctype);
                        }*/
                        driver.println(expected.toString());
                        driver.println("Actual results:");
                        //System.err.println(result.serialization);
                        driver.println(value.toString());
                    }
                }
                return success;
            } catch (XPathException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    private class RemoveWhitespace implements ItemMappingFunction<NodeInfo, NodeInfo> {
        public NodeInfo mapItem(NodeInfo item) {
            boolean isWhite = item.getNodeKind() == Type.TEXT && Whitespace.isWhite(item.getStringValueCS());
            return isWhite ? null : item;
        }
    }

    private boolean assertPermutation(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc) throws SaxonApiException {
        // TODO: extend this to handle nodes (if required)
        if (isException()) {
            return false;
        } else {
            try {
                int expectedItems = 0;
                HashSet<AtomicMatchKey> expected = new HashSet<>();
                XPathSelector s = assertXpc.compile("(" + assertion.getStringValue() + ")").load();
                s.setVariable(new QName("result"), result.value); // not used, but we declared it
                StringCollator collator = CodepointCollator.getInstance();
                XPathContext context = s.getUnderlyingXPathContext().getXPathContextObject();
                for (XdmItem item : s) {
                    expectedItems++;
                    AtomicValue value = (AtomicValue) item.getUnderlyingValue();
                    AtomicMatchKey comparable = value.isNaN() ?
                            AtomicSortComparer.COLLATION_KEY_NaN :
                            value.getXPathComparable(false, collator, context.getImplicitTimezone());
                    expected.add(comparable);
                }
                int actualItems = 0;
                for (XdmItem item : getPrincipalResult()) {
                    actualItems++;
                    AtomicValue value = (AtomicValue) item.getUnderlyingValue();
                    AtomicMatchKey comparable = value.isNaN() ?
                            AtomicSortComparer.COLLATION_KEY_NaN :
                            value.getXPathComparable(false, collator, context.getImplicitTimezone());
                    if (!expected.contains(comparable)) {
                        return false;
                    }
                }
                return actualItems == expectedItems;
            } catch (NoDynamicContextException e) {
                System.err.println("Comparison of results failed - no timezone available");
                return false;
            }
        }
    }

    private boolean assertDeepEq(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            assertXpc.declareVariable(new QName("result"));
            XPathSelector s = assertXpc.compile("deep-equal($result , (" + assertion.getStringValue() + "))").load();
            s.setVariable(new QName("result"), result.value);
            return ((XdmAtomicValue) s.evaluate()).getBooleanValue();
        }
    }

    private boolean assertEq(XdmNode assertion, SingleResultDoc result, XPathCompiler assertXpc) throws SaxonApiException {
        if (isException()) {
            return false;
        } else {
            assertXpc.declareVariable(new QName("result"));
            XPathSelector s = assertXpc.compile("$result eq " + assertion.getStringValue()).load();
            s.setVariable(new QName("result"), result.value);
            XdmAtomicValue item;
            try {
                item = (XdmAtomicValue) s.evaluateSingle();
            } catch (SaxonApiException e) {
                System.err.println("assert-eq failed - " + e.getMessage());
                return false;
            }
            return item != null && item.getBooleanValue();
        }
    }

    public boolean compareExpectedError(XdmNode assertion) {
        String expectedError = assertion.attribute("code");
        QName expectedErrorQ;
        if (expectedError.equals("*")) {
            expectedErrorQ = null;
        } else if (expectedError.startsWith("Q{")) {
            expectedErrorQ = QName.fromEQName(expectedError);
        } else if (expectedError.contains(":")) {
            try {
                NamespaceResolver resolver = new InscopeNamespaceResolver(assertion.getUnderlyingNode());
                StructuredQName sq = StructuredQName.fromLexicalQName(expectedError, false, false, resolver);
                expectedErrorQ = new QName(sq);
            } catch (XPathException e) {
                expectedErrorQ = new QName("", "", "unknown-prefix-in-lexical-QName");
            }
        } else {
            expectedErrorQ = new QName("err", NamespaceConstant.ERR, expectedError);
        }
        //noinspection ThrowableResultOfMethodCallIgnored
        boolean ok = expectedError.equals("*") ||
                (getException().getErrorCode() != null &&
                        getException().getErrorCode().equals(expectedErrorQ)) ||
                hasReportedError(expectedError);
        if (ok) {
            wrongError = null;
        } else if (expectedErrorQ != null && errorsReported != null && !errorsReported.isEmpty()) {
            FastStringBuffer fsb = new FastStringBuffer(100);
            fsb.append("Expected ");
            fsb.append(expectedErrorQ.getLocalName());
            fsb.append("; got ");
            for (String e : errorsReported) {
                fsb.append(e);
                fsb.append("|");
            }
            fsb.setLength(fsb.length() - 1);
            wrongError = fsb.toString();
        }
        return ok;
    }


}