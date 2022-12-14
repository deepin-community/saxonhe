////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2018 Saxonica Limited.
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
// If a copy of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// This Source Code Form is "Incompatible With Secondary Licenses", as defined by the Mozilla Public License, v. 2.0.
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package net.sf.saxon.testdriver;

import net.sf.saxon.Configuration;
import net.sf.saxon.dom.DocumentWrapper;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.expr.sort.SimpleCollation;
import net.sf.saxon.lib.*;
import net.sf.saxon.om.*;
import net.sf.saxon.query.QueryResult;
import net.sf.saxon.resource.AbstractResourceCollection;
import net.sf.saxon.resource.XmlResource;
import net.sf.saxon.s9api.*;
import net.sf.saxon.trans.DecimalFormatManager;
import net.sf.saxon.trans.DecimalSymbols;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.util.FastStringBuffer;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static net.sf.saxon.s9api.streams.Steps.child;
import static net.sf.saxon.testdriver.Xslt30TestSuiteDriverHE.isTrue;

/**
 * This class represents a collection of resources (source documents, schemas, collections etc) used for a number
 * of test cases.
 */

public class Environment implements URIResolver {

    public static class CatalogResource {
        public File file;
        public URL url;
        public String mediaType;
        public String encoding;
        public String uri;
        public XdmNode doc;
    }

    public Processor processor;
    public Map<String, XdmNode> sourceDocs = new HashMap<>();
    public Map<String, String> streamedSecondaryDocs = new HashMap<>();
    public String streamedContent;
    public File streamedFile;
    public String streamedInputValidation = "skip";
    public XPathCompiler xpathCompiler;
    public XQueryCompiler xqueryCompiler;
    public XsltCompiler xsltCompiler;
    public XsltExecutable xsltExecutable;
    public File exportedStylesheet;
    public XdmItem contextItem;
    public HashMap<QName, XdmValue> params = new HashMap<>();
    public boolean usable = true;
    public boolean failedToBuild = false;
    public FastStringBuffer paramDeclarations = new FastStringBuffer(256);
    public FastStringBuffer paramDecimalDeclarations = new FastStringBuffer(256);
    public UnparsedTextURIResolver unparsedTextResolver;
    public List<ResetAction> resetActions = new ArrayList<>();
    public boolean schemaAvailable = false;
    public boolean outputTree = true;
    public boolean outputSerialize = false;
    public Map<String, CatalogResource> catalogResources = new HashMap<>();
    public Map<String, List<String>> jsCollections;


    /**
     * Construct a local default environment for a test set
     */

    public static Environment createLocalEnvironment(URI baseURI, int generateByteCode, boolean unfolded, Spec spec, TestDriver testDriver) {
        final Environment environment = new Environment();
        environment.processor = new Processor(!testDriver.driverProc.getSaxonEdition().equals("HE"));
        //testDriver.getLicensor().activate(environment.processor);
        //AutoActivate.activate(environment.processor);
        environment.processor.setConfigurationProperty(Feature.OPTIMIZATION_LEVEL, testDriver.optimizerFlags);
        configureByteCode(testDriver, environment, generateByteCode);

        environment.xpathCompiler = environment.processor.newXPathCompiler();
        environment.xpathCompiler.setBaseURI(baseURI);
        environment.xqueryCompiler = environment.processor.newXQueryCompiler();
        environment.xqueryCompiler.setBaseURI(baseURI);
        environment.xsltCompiler = environment.processor.newXsltCompiler();
        if (testDriver.runWithJS) {
            environment.xsltCompiler.setTargetEdition("JS");
        }
        if (environment.processor.getSaxonEdition().equals("EE")) {
            environment.xsltCompiler.setJustInTimeCompilation(testDriver.jitFlag);
        }
        environment.xsltCompiler.setRelocatable(testDriver.relocatable);
        if (spec == Spec.XT30) {
            //environment.xsltCompiler.setXsltLanguageVersion("3.0");
            environment.xpathCompiler.setLanguageVersion("3.1");
        } else if (spec == Spec.XT20) {
            throw new AssertionError("Unsupported spec: XSLT 2.0");
        }
        if (unfolded) {
            testDriver.addInjection(environment.xqueryCompiler);
        }
        if (testDriver.tracing) {
            environment.xsltCompiler.setCompileWithTracing(true);
        }
        environment.processor.getUnderlyingConfiguration().setDefaultCollection(null);
        environment.processor.setConfigurationProperty(Feature.STABLE_UNPARSED_TEXT, true);
        return environment;
    }

    /**
     * Construct an Environment
     *
     * @param xpc          the XPathCompiler used to process the catalog file
     * @param env          the Environment element in the catalog file
     * @param environments the set of environments to which this one should be added (may be null)
     * @return the constructed Environment object
     * @throws SaxonApiException
     */

    public static Environment processEnvironment(
            final TestDriver driver,
            XPathCompiler xpc, XdmNode env, Map<String, Environment> environments, Environment defaultEnvironment)
            throws SaxonApiException {
        Environment environment = new Environment();
        String name;
        name = env.attribute("name");
        if (name != null && !driver.quiet) {
            System.err.println("Loading environment " + name);
        }
        environment.processor = new Processor(!driver.driverProc.getSaxonEdition().equals("HE"));
        if (defaultEnvironment != null) {
            environment.processor.setConfigurationProperty(Feature.XSD_VERSION,
                    defaultEnvironment.processor.getConfigurationProperty(Feature.XSD_VERSION));
        }
        driver.prepareForSQL(environment.processor);
        //AutoActivate.activate(environment.processor);


        // TODO temporary
        environment.processor.setConfigurationProperty(Feature.EXPATH_FILE_DELETE_TEMPORARY_FILES, true);
        environment.processor.setConfigurationProperty(Feature.LINE_NUMBERING, true);

        configureByteCode(driver, environment, driver.generateByteCode);

        environment.xpathCompiler = environment.processor.newXPathCompiler();
        environment.xpathCompiler.setBaseURI(env.getBaseURI());
        environment.xqueryCompiler = environment.processor.newXQueryCompiler();
        environment.xqueryCompiler.setBaseURI(env.getBaseURI());
        if (driver.spec.shortSpecName.equals("XT")) {
            environment.xsltCompiler = environment.processor.newXsltCompiler();
            //environment.xsltCompiler.setXsltLanguageVersion(driver.spec.version);
            if (driver.runWithJS) {
                environment.xsltCompiler.setTargetEdition("JS");
            }
            if (environment.processor.getSaxonEdition().equals("EE")) {
                environment.xsltCompiler.setJustInTimeCompilation(driver.jitFlag);
            }
            environment.xsltCompiler.setRelocatable(driver.relocatable);
        }

        if (driver.unfolded) {
            driver.addInjection(environment.xqueryCompiler);
        }

        DocumentBuilder builder = environment.processor.newDocumentBuilder();
        builder.setTreeModel(driver.treeModel);
        environment.sourceDocs = new HashMap<>();
        if (environments != null && name != null) {
            environments.put(name, environment);
        }

        for (XdmNode prop : env.children("http://saxon.sf.net/", "property")) {
            String propName = prop.attribute("name");
            String propValue = prop.attribute("value");
            environment.processor.setConfigurationProperty(propName, propValue);
        }

        // QT3 - dependency as sibling of environment
        for (XdmNode dependency : env.getParent().children("dependency")) {
            if (!driver.ensureDependencySatisfied(dependency, environment)) {
                environment.usable = false;
            }
        }

        // XSLT3 - dependencies as sibling of environment
        for (XdmNode dependency : env.getParent().select(child("dependencies").then(child("*"))).asList()) {
                //xpc.evaluate("../dependencies/*", env)) {
            if (!driver.ensureDependencySatisfied(dependency, environment)) {
                environment.usable = false;
            }
        }

        // set the base URI if specified

        setBaseUri(driver, xpc, env, environment);

        // set any requested collations

        registerCollations(xpc, env, environment);

        // declare the requested namespaces

        declareNamespaces(xpc, env, environment);

        // load the requested schema documents

        SchemaManager manager = environment.processor.getSchemaManager();
        boolean validateSources = loadSchemaDocuments(driver, xpc, env, environment, manager);

        // load the requested source documents

        loadSourceDocuments(driver, xpc, env, environment, builder, manager, validateSources);

        // create a collection URI resolver to handle the requested collections

        createCollectionUriResolver(driver, xpc, env, environment, builder);

        // create an unparsed text resolver to handle any unparsed text resources

        createUnparsedTextResolver(driver, xpc, env, environment);

        // register any required decimal formats

        registerDecimalFormats(driver, xpc, env, environment);

        // declare any variables

        declareExternalVariables(driver, xpc, env, environment);

        // declare any output controls
        declareOutputControls(driver, xpc, env, environment);

        // handle requested context item
        for (XdmNode param : env.children("context-item")) {
            String select = param.attribute("select");
            XdmValue value = xpc.evaluate(select, null);
            environment.contextItem = value.itemAt(0);
        }

        // compile any stylesheet packages defined as part of the environment
        // Support this only in EE - an unusable environment in PE/HE
        for (XdmItem stylesheet : xpc.evaluate("package[@role='secondary']", env)) {
            String fileName = ((XdmNode) stylesheet).attribute("file");
            Source styleSource = new StreamSource(((XdmNode) env).getBaseURI().resolve(fileName).toString());
            try {
                XsltPackage pkg = environment.xsltCompiler.compilePackage(styleSource);
                environment.xsltCompiler.importPackage(pkg);
                // Make the package available for use from fn:transform
                environment.processor.getUnderlyingConfiguration().getDefaultXsltCompilerInfo().getPackageLibrary().addPackage(pkg.getUnderlyingPreparedPackage());
            } catch (SaxonApiException e) {
                e.printStackTrace();
                driver.println("**** failure while compiling environment-defined stylesheet package " + fileName);
                environment.failedToBuild = true;
                environment.usable = false;
            } catch (Exception e) {
                e.printStackTrace();
                driver.println("****Failure " + e + " in compiling environment " + name);
                environment.failedToBuild = true;
                environment.usable = false;
            }
        }

        // compile any stylesheet defined as part of the environment (only one allowed)
        // use the XSLT processor version associated with the stylesheet/@version attribute
        for (XdmItem stylesheet : xpc.evaluate("stylesheet[not(@role='secondary')]", env)) {
            String fileName = ((XdmNode) stylesheet).attribute("file");
            Source styleSource = new StreamSource(env.getBaseURI().resolve(fileName).toString());
            try {
                if (driver.export) {
                    if (driver.runWithJS) {
                        String sourceFile = env.getBaseURI().resolve(fileName).toString();
                        environment.exportedStylesheet = driver.exportStylesheet(environment.xsltCompiler, sourceFile);
                    } else if (driver.xxCompilerLocation != null && !environment.xsltCompiler.isSchemaAware()) {
                        if (driver.xxCompiler == null) {

                            XdmMap options = new XdmMap();
                            boolean assertionsEnabled = environment.xsltCompiler.isAssertionsEnabled();
                            options = options.put(new XdmAtomicValue("enable-assertions"), new XdmAtomicValue(assertionsEnabled));

                            XsltCompiler c = driver.driverProc.newXsltCompiler();
                            c.setAssertionsEnabled(true);
                            c.setParameter(new QName("FAST_JAVA_XPATH"), new XdmAtomicValue(true));
                            c.setParameter(new QName("options"), options);
                            c.setFastCompilation(true); // TODO: temporary

                            driver.xxCompiler = c.compile(new StreamSource(new File(driver.xxCompilerLocation)));
                        }
                        File exportFile = new File(driver.resultsDir + "/export/" + name + ".sef");
                        Xslt30Transformer transformer = driver.xxCompiler.load30();
                        transformer.setInitialMode(new QName("compile-complete"));
                        Serializer serializer = driver.driverProc.newSerializer(exportFile);
                        transformer.applyTemplates(styleSource, serializer);
                        environment.xsltExecutable = environment.xsltCompiler.loadExecutablePackage(exportFile.toURI());
                    } else {
                        File exportFile = new File(driver.resultsDir + "/export/" + name + ".sef");
                        XsltPackage compiledPack = environment.xsltCompiler.compilePackage(styleSource);
                        compiledPack.save(exportFile);
                        environment.xsltExecutable = environment.xsltCompiler.loadExecutablePackage(exportFile.toURI());
                    }
                } else {
                    environment.xsltExecutable = environment.xsltCompiler.compile(styleSource);
                }
            } catch (SaxonApiException e) {
                //e.printStackTrace();
                driver.println("**** failure while compiling environment-defined stylesheet " + fileName);
                environment.failedToBuild = true;
                environment.usable = false;
            }
        }


        return environment;
    }

    private static void configureByteCode(TestDriver driver, Environment environment, int generate) {
        if (environment.processor.getSaxonEdition().equals("EE")) {
            if (generate == 0) {
                environment.processor.setConfigurationProperty(Feature.GENERATE_BYTE_CODE, true);
                environment.processor.setConfigurationProperty(Feature.DEBUG_BYTE_CODE, false);
                environment.processor.setConfigurationProperty(Feature.MAX_COMPILED_CLASSES, 1000000);
            } else  if (generate > 0) {
                environment.processor.setConfigurationProperty(FeatureKeys.GENERATE_BYTE_CODE, generate);
                environment.processor.setConfigurationProperty(Feature.DEBUG_BYTE_CODE, false);
                environment.processor.setConfigurationProperty(Feature.MAX_COMPILED_CLASSES, 1000000);
            } else {
                environment.processor.setConfigurationProperty(Feature.GENERATE_BYTE_CODE, false);
                environment.processor.setConfigurationProperty(Feature.DEBUG_BYTE_CODE, false);
                //environment.processor.setConfigurationProperty(Feature.ALLOW_MULTITHREADING, "false"); // TODO should have a separate option for this
            }
            if (generate >= 0 && driver.isDebugByteCode()) {
                environment.processor.setConfigurationProperty(Feature.DEBUG_BYTE_CODE, true);
                environment.processor.setConfigurationProperty(Feature.DEBUG_BYTE_CODE_DIR, "debugByteCode");
                environment.processor.setConfigurationProperty(Feature.MONITOR_HOT_SPOT_BYTE_CODE, true);
                driver.driverProc.setConfigurationProperty(Feature.DEBUG_BYTE_CODE, true);
                driver.driverProc.setConfigurationProperty(Feature.DEBUG_BYTE_CODE_DIR, "debugByteCode");
                driver.driverProc.setConfigurationProperty(Feature.MONITOR_HOT_SPOT_BYTE_CODE, true);
                driver.driverProc.setConfigurationProperty(Feature.OPTIMIZATION_LEVEL, "-f");
            }
        }
    }

    private static void declareOutputControls(TestDriver driver, XPathCompiler xpc, XdmItem env, Environment environment) throws SaxonApiException {
        String needsSerialization = xpc.evaluate("string((output/@serialize,'no')[1])", env).toString();
        environment.outputSerialize = "yes".equals(needsSerialization);
        String needsTree = xpc.evaluate("string((output/@tree,'yes')[1])", env).toString();
        environment.outputTree = "yes".equals(needsTree);

    }

    private static void setBaseUri(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment) {
        for (XdmNode base : env.children("static-base-uri")) {
            String uri = base.attribute("uri");
            if (uri == null || "#UNDEFINED".equals(uri)) {
                environment.xqueryCompiler.setBaseURI(null);
                driver.println("**** Error: The BaseURI values null and #UNDEFINED are not supported in this test driver");
            } else {
                try {
                    environment.xpathCompiler.setBaseURI(new URI(uri));
                    environment.xqueryCompiler.setBaseURI(new URI(uri));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    driver.println("**** invalid base URI " + uri);
                }
            }
        }
    }

    private static void registerCollations(XPathCompiler xpc, XdmNode env, Environment environment) throws SaxonApiException {
        for (XdmNode base : env.children("collation")) {
            String uri = base.attribute("uri");
            if (uri.equals("http://www.w3.org/2010/09/qt-fots-catalog/collation/caseblind") ||
                    uri.equals("http://www.w3.org/xslts/collation/caseblind")) {
                Configuration config = xpc.getProcessor().getUnderlyingConfiguration();
                try {
                    StringCollator collator = config.getCollation("http://saxon.sf.net/collation?ignore-case=yes");
                    environment.processor.declareCollation(uri, ((SimpleCollation) collator).getComparator());
                } catch (XPathException e) {
                    throw new SaxonApiException(e);
                }
            }
            String defaultAtt = base.attribute("default");
            if (defaultAtt != null && (defaultAtt.trim().equals("true") || defaultAtt.trim().equals("1"))) {
                environment.xpathCompiler.declareDefaultCollation(uri);
                environment.xqueryCompiler.declareDefaultCollation(uri);
                environment.xsltCompiler.declareDefaultCollation(uri);
            }
        }
    }

    private static void declareNamespaces(XPathCompiler xpc, XdmNode env, Environment environment) throws SaxonApiException {
        for (XdmNode nsElement : env.children("namespace")) {
            String prefix = nsElement.attribute("prefix");
            String uri = nsElement.attribute("uri");
            environment.xpathCompiler.declareNamespace(prefix, uri);
            environment.xqueryCompiler.declareNamespace(prefix, uri);
            if (uri.equals("http://expath.org/ns/file")) {
                // For EXPath file tests, set the EXPath base directory to the catalog directory
                String base = nsElement.getBaseURI().toString();
                if (base.startsWith("file:///")) {
                    base = base.substring(7);
                } else if (base.startsWith("file:/")) {
                    base = base.substring(5);
                }
                File file = new File(base);
                file = file.getParentFile();
                System.setProperty("expath.base.directory", file.getAbsolutePath());
            }
        }
    }

    private static boolean loadSchemaDocuments(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment, SchemaManager manager) throws SaxonApiException {
        boolean validateSources = false;
        for (XdmNode schema : env.children("schema")) {
            String role = schema.attribute("role");
            String xsdVersion = schema.attribute("xsd-version");
            if (manager == null) {
                driver.println("*** Processor is not schema aware");
                environment.usable = false;
                return false;
            }
            if (!driver.treeModel.isSchemaAware()) {
                driver.println("*** Tree model is not schema aware");
                environment.usable = false;
                return false;
            }
            if (xsdVersion != null) {
                manager.setXsdVersion(xsdVersion);
            } else {
                manager.setXsdVersion("1.0");
            }
            if (!"secondary".equals(role)) {
                String href = schema.attribute("file");
                String ns = schema.attribute("uri");
                if (href == null) {
                    try {
                        Source[] sources = manager.getSchemaURIResolver().resolve(ns, null, new String[0]);
                        manager.load(sources[0]);
                    } catch (Exception e) {
                        driver.println("*** Failed to load schema by URI: " + ns + " - " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    File file = new File(((XdmNode) env).getBaseURI().resolve(href));
                    try {
                        manager.load(new StreamSource(file));
                    } catch (SaxonApiException err) {
                        driver.println("*** Failed to load schema: " + href + " - " + err.getMessage());
                    } catch (NullPointerException err) {
                        err.printStackTrace();
                        driver.println("*** NPE: Failed to load schema: " + href + " - " + err.getMessage());
                    }
                }
                xpc.importSchemaNamespace(ns);
                environment.xpathCompiler.importSchemaNamespace(ns);
                if ("source-reference".equals(role)) {
                    validateSources = true;
                }
                if ("stylesheet-import".equals(role)) {
                    environment.xsltCompiler.setSchemaAware(true);
                }
            }
        }
        return validateSources;
    }

    private static void declareExternalVariables(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment) throws SaxonApiException {
        for (XdmNode param : env.children("param")) {
            String varName = param.attribute("name");
            XdmValue value;
            String source = param.attribute("source");
            if (source != null) {
                XdmNode sourceDoc = environment.sourceDocs.get(source);
                if (sourceDoc == null) {
                    driver.println("**** Unknown source document " + source);
                }
                value = sourceDoc;
            } else {
                String select = param.attribute("select");
                value = xpc.evaluate(select, null);
            }
            boolean isStatic = isTrue("static").test(param);
            QName varQName;
            int colon = varName.indexOf(':');
            if (colon >= 0) {
                NamespaceResolver resolver = new InscopeNamespaceResolver(param.getUnderlyingNode());
                String namespace = resolver.getURIForPrefix(varName.substring(0, colon), false);
                varQName = new QName(namespace, varName);
            } else {
                varQName = new QName(varName);
            }
            environment.xpathCompiler.declareVariable(varQName);
            if (isTrue("declared").test(param)) {
                // no action
            } else {
                environment.paramDeclarations.append("declare variable $" + varName + " external; ");
            }
            if (isStatic) {
                environment.xsltCompiler.setParameter(varQName, value);
                System.err.println("set " + varQName + " = " + value);
            } else {
                environment.params.put(varQName, value);
            }
        }
    }

    private static void registerDecimalFormats(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment) throws SaxonApiException {
        for (XdmNode decimalFormat : env.children("decimal-format")) {
            DecimalFormatManager dfm = environment.xpathCompiler.getUnderlyingStaticContext().getDecimalFormatManager();

            String formatName = decimalFormat.attribute("name");
            StructuredQName formatQName = null;
            if (formatName != null) {
                if (formatName.indexOf(':') < 0) {
                    formatQName = new StructuredQName("", "", formatName);
                } else {
                    try {
                        formatQName = StructuredQName.fromLexicalQName(formatName, false, true,
                                new InscopeNamespaceResolver(decimalFormat.getUnderlyingNode()));
                    } catch (XPathException e) {
                        driver.println("**** Invalid QName as decimal-format name");
                        formatQName = new StructuredQName("", "", "error-name");
                    }
                }
                environment.paramDecimalDeclarations.append("declare decimal-format " + formatQName.getEQName() + " ");
            } else {
                environment.paramDecimalDeclarations.append("declare default decimal-format ");
            }
            DecimalSymbols symbols = formatQName == null ? dfm.getDefaultDecimalFormat() : dfm.obtainNamedDecimalFormat(formatQName);
            symbols.setHostLanguage(Configuration.XQUERY, driver.spec.getNumericVersion());
            for (XdmItem decimalFormatAtt : xpc.evaluate("@* except @name", (XdmNode) decimalFormat)) {
                XdmNode formatAttribute = (XdmNode) decimalFormatAtt;
                String property = formatAttribute.getNodeName().getLocalName();
                String value = formatAttribute.getStringValue();
                environment.paramDecimalDeclarations.append(property + "=\"" + value + "\" ");
                try {
                    switch (property) {
                        case "decimal-separator":
                            symbols.setDecimalSeparator(value);
                            break;
                        case "grouping-separator":
                            symbols.setGroupingSeparator(value);
                            break;
                        case "exponent-separator":
                            symbols.setExponentSeparator(value);
                            break;
                        case "infinity":
                            symbols.setInfinity(value);
                            break;
                        case "NaN":
                            symbols.setNaN(value);
                            break;
                        case "minus-sign":
                            symbols.setMinusSign(value);
                            break;
                        case "percent":
                            symbols.setPercent(value);
                            break;
                        case "per-mille":
                            symbols.setPerMille(value);
                            break;
                        case "zero-digit":
                            symbols.setZeroDigit(value);
                            break;
                        case "digit":
                            symbols.setDigit(value);
                            break;
                        case "pattern-separator":
                            symbols.setPatternSeparator(value);
                            break;
                        default:
                            driver.println("**** Unknown decimal format attribute " + property);
                            break;
                    }
                } catch (XPathException e) {
                    driver.println("**** " + e.getMessage());
                }
            }
            environment.paramDecimalDeclarations.append(";");
            try {
                symbols.checkConsistency(formatQName);
            } catch (XPathException err) {
                driver.println("**** " + err.getMessage());
            }

        }
    }

    private static void createUnparsedTextResolver(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment) throws SaxonApiException {
        final HashMap<URI, Object> resources = new HashMap<>();
        final HashMap<URI, String> encodings = new HashMap<>();
        for (XdmNode resource : env.children("resource")) {
            CatalogResource res = new CatalogResource();
            res.uri = resource.attribute("uri");
            String href = resource.attribute("file");
            res.encoding = resource.attribute("encoding");
            res.mediaType = resource.attribute("media-type");
            if (href != null) {
                Object obj = null;

                if (href.startsWith("http")) {
                    try {
                        res.url = new URL(href);
                        obj = res.url;
                    } catch (MalformedURLException e) {
                        e.printStackTrace();
                    }
                } else {
                    res.file = new File(((XdmNode) env).getBaseURI().resolve(href));
                    obj = res.file;
                }
                try {
                    resources.put(new URI(res.uri), obj);
                    encodings.put(new URI(res.uri), res.encoding);
                    URI abs = ((XdmNode) resource).getBaseURI().resolve(res.uri);
                    resources.put(abs, obj);
                    encodings.put(abs, res.encoding);
                    environment.catalogResources.put(abs.toString(), res);

                } catch (URISyntaxException e) {
                    driver.println("** Invalid URI in environment: " + e.getMessage());
                }
                if (res.mediaType != null && res.mediaType.endsWith("xquery")) {
                    driver.registerXQueryModule(res.uri, res.file);
                }
            }

        }
        if (!resources.isEmpty()) {
            environment.unparsedTextResolver =
                    (absoluteURI, encoding, config) -> {
                        if (encoding == null) {
                            encoding = encodings.get(absoluteURI);
                        }
                        if (encoding == null) {
                            encoding = "utf-8";
                        }
                        try {
                            // The following is necessary to ensure that encoding errors are not recovered.
                            Charset charset = Charset.forName(encoding);
                            CharsetDecoder decoder = charset.newDecoder();
                            decoder = decoder.onMalformedInput(CodingErrorAction.REPORT);
                            decoder = decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
                            Object obj = resources.get(absoluteURI);
                            if (obj instanceof File) {
                                return new BufferedReader(new InputStreamReader(new FileInputStream((File) obj), decoder));
                            } else {
                                URL resource = (URL) resources.get(absoluteURI);
                                if (resource == null) {
                                    resource = absoluteURI.toURL();
                                    //throw new XPathException("Unparsed text resource " + absoluteURI + " not registered in catalog", "FOUT1170");
                                }
                                InputStream in = resource.openConnection().getInputStream();
                                return new BufferedReader(new InputStreamReader(in, decoder));
                            }
                            //   return new InputStreamReader(new FileInputStream(resources.get(absoluteURI)), encoding);
                        } catch (IOException ioe) {
                            throw new XPathException(ioe.getMessage(), "FOUT1170");
                        } catch (IllegalCharsetNameException | UnsupportedCharsetException icne) {
                            throw new XPathException("Invalid encoding name: " + encoding, "FOUT1190");
                        }
                    };
        }
    }

    private final static String DEFAULT_COLLECTION_URI = "http://www.w3.org/qt3-test-suite/default.collection.uri";

    private static void createCollectionUriResolver(final TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment, DocumentBuilder builder) throws SaxonApiException {
        if (!environment.usable) {
            // Can't run collection tests with XQuery 1.0
            return;
        }
        final HashMap<String, ResourceCollection> collections = new HashMap<>();
        AbstractResourceCollection collectioni = null;
        final Configuration config = environment.processor.getUnderlyingConfiguration();
        for (XdmNode coll : env.children("collection")) { //  xpc.evaluate("collection", env)) {
            final List<Resource> resourcesi = new ArrayList<>();

            String collectionURI = coll.attribute("uri");
            if (collectionURI == null) {
                collectionURI = "";
            }

            if (collectionURI.isEmpty()) {
                collectionURI = DEFAULT_COLLECTION_URI;
                config.setDefaultCollection(DEFAULT_COLLECTION_URI);
            }
            URI u;
            try {
                u = new URI(collectionURI);
            } catch (URISyntaxException e) {
                driver.println("**** Invalid collection URI " + collectionURI);
                break;
            }
            if (!collectionURI.equals("") && !u.isAbsolute()) {
                u = ((XdmNode) env).getBaseURI().resolve(collectionURI);
                collectionURI = u.toString();
            }

            final String cURI = collectionURI;

            collectioni = new AbstractResourceCollection(config) {


                public void addResource(Resource r) {
                    resourcesi.add(r);
                }

                public String getCollectionURI() {
                    return cURI;
                }

                public Iterator<String> getResourceURIs(XPathContext context) {
                    List<String> resourceUris = new ArrayList<>();
                    for (Resource resource : resourcesi) {
                        resourceUris.add(resource.getResourceURI());
                    }
                    return resourceUris.iterator();
                }

                public Iterator<Resource> getResources(XPathContext context) {
                    return resourcesi.iterator();
                }

                @Override
                public boolean isStable(XPathContext context) {
                    return true;
                }
            };

            for (XdmNode source : coll.children("source")) {
                String href = source.attribute("file");
                String frag = null;
                int hash = href.indexOf('#');
                if (hash > 0) {
                    frag = href.substring(hash + 1);
                    href = href.substring(0, hash);
                }
                File file = new File(env.getBaseURI().resolve(href));
                String xinc = source.attribute("xinclude");
                XdmNode doc;
                if ("true".equals(xinc)) {
                    StreamSource ss = new StreamSource(file);
                    AugmentedSource as = AugmentedSource.makeAugmentedSource(ss);
                    as.setXIncludeAware(true);
                    doc = builder.build(as);
                } else {
                    doc = builder.build(file);
                }
                if (frag != null) {
                    XdmNode selected = (XdmNode) environment.xpathCompiler.evaluateSingle("id('" + frag + "')", doc);
                    if (selected == null) {
                        driver.println("**** Fragment not found: " + frag);
                        break;
                    }

                    resourcesi.add(new XmlResource(config, selected.getUnderlyingNode()));
                } else {
                    resourcesi.add(new XmlResource(config, doc.getUnderlyingNode()));
                }
                environment.sourceDocs.put(href, doc);
            }
            for (XdmNode resource : coll.children("resources")) {
                String uri = resource.attribute("uri");
                String href = resource.attribute("file");
                String encoding = resource.attribute("encoding");
                String media = resource.attribute("media-type");
                AbstractResourceCollection.InputDetails details = new AbstractResourceCollection.InputDetails();
                details.encoding = encoding;
                details.contentType = media;
                if (href != null) {
                    if (href.startsWith("http")) {
                        try {
                            URL url = new URL(href);
                            URLConnection connection = url.openConnection();
                            if (details.contentType == null) {
                                details.contentType = connection.getContentType();
                            }
                            details.inputStream = connection.getInputStream();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        File file = new File(env.getBaseURI().resolve(href));
                        try {
                            details.inputStream = new FileInputStream(file);
                            Resource resourcej = collectioni.makeResource(config, details, file.toURI().toString());
                            if (resourcej != null) {
                                resourcesi.add(resourcej);
                            } else {
                                driver.println("** Error in building collection environment: Resource " + href + " not found: ");
                            }
                        } catch (IOException e) {
                            driver.println("** IO Error in building collection environment: " + e.getMessage());

                        } catch (XPathException e) {
                            driver.println("** Error in building collection environment: " + e.getMessage());
                        }
                        if (media != null && href.endsWith("xquery")) {
                            driver.registerXQueryModule(uri, file);
                        }
                    }

                }
            }
            for (XdmNode query : coll.children("query")) {
                String queryExpr = query.getStringValue();
                XQueryEvaluator evaluator = environment.xqueryCompiler.compile(queryExpr).load();
                XdmValue result = evaluator.evaluate();

                for (final XdmItem itemi : result) {
                    resourcesi.add(new Resource() {
                        public String getResourceURI() {
                            return "";
                        }

                        public Item getItem(XPathContext context) {
                            return itemi.getUnderlyingValue();
                        }

                        public String getContentType() {
                            return null;
                        }
                    });
                }
            }
            collections.put(collectionURI, collectioni);

        }

        if (collections != null) {
            environment.processor.getUnderlyingConfiguration().setCollectionFinder(new CollectionFinder() {
                public ResourceCollection findCollection(XPathContext context, String collectionURI) throws XPathException {
                if (collectionURI == null) {
                   collectionURI = "";
                }
                return collections.get(collectionURI);
                }
            });

        }

        if (driver.runWithJS) {
            environment.jsCollections = new HashMap<>();
            for (Map.Entry<String, ResourceCollection> entry : collections.entrySet()) {
                String uri = entry.getKey();
                List<String> lexicalContents = new ArrayList<>();
                ResourceCollection contents = entry.getValue();
                XPathContext context = environment.processor.getUnderlyingConfiguration().getConversionContext();
                try {
                    Iterator<? extends Resource> iter = contents.getResources (context);
                    while (iter.hasNext()) {
                        Resource resource = iter.next();
                        Item item = resource.getItem(context);
                        if (item instanceof NodeInfo) {
                            String lexicalXml = QueryResult.serialize((NodeInfo)item);
                            lexicalContents.add(lexicalXml);
                        }
                    }
                } catch (XPathException e) {
                    e.printStackTrace();
                }
                environment.jsCollections.put(uri, lexicalContents);
            }
        }
    }

    private static void loadSourceDocuments(TestDriver driver, XPathCompiler xpc, XdmNode env, Environment environment, DocumentBuilder builder, SchemaManager manager, boolean validateSources) throws SaxonApiException {
        for (XdmNode source : env.children("source")) {

            CatalogResource res = new CatalogResource();
            String rawUri = source.attribute("uri");
            String role = source.attribute("role");
            if (rawUri != null) {
                res.uri = env.getBaseURI().resolve(rawUri).toString();
                environment.catalogResources.put(res.uri, res);
            } else if (".".equals(role)) {
                environment.catalogResources.put("", res);
            }
            res.mediaType = source.attribute("media-type");
            if ("application/xml".equals(res.mediaType)) {
                // MHK 2016-04-07: why??
                continue;
            }
            String validation = source.attribute("validation");
            if (validation == null) {
                validation = "skip";
            }
            String streaming = source.attribute("streaming");
            TreeModel selectedTreeModel = builder.getTreeModel();
            if (!validateSources && validation.equals("skip")) {
                builder.setSchemaValidator(null);
            } else if (manager == null) {
                environment.usable = false;
            } else {
                if (!selectedTreeModel.isSchemaAware()) {
                    builder.setTreeModel(TreeModel.TINY_TREE);
                }
                SchemaValidator validator = manager.newSchemaValidator();
                validator.setLax("lax".equals(validation));
                builder.setSchemaValidator(validator);
                environment.xpathCompiler.setSchemaAware(true);
                environment.xqueryCompiler.setSchemaAware(true);
                if (environment.xsltCompiler != null) {
                    environment.xsltCompiler.setSchemaAware(true);
                }
            }

            String href = source.attribute("file");
            String select = source.attribute("select");
            String xinc = source.attribute("xinclude");
            if ("true".equals(streaming)) {
                if (".".equals(role)) {
                    if (href == null) {
                        environment.streamedContent = xpc.evaluate("string(content)", source).toString();
                    } else {
                        environment.streamedFile = new File(((XdmNode) env).getBaseURI().resolve(href));
                        res.file = environment.streamedFile;
                    }
                    environment.streamedInputValidation = validation;
                } else {
                    File file = new File(((XdmNode) env).getBaseURI().resolve(href));
                    environment.streamedSecondaryDocs.put(file.toURI().toString(), validation);
                    res.file = file;
                }
            } else if (driver.runWithJS || driver.isAltova) {
                if (select != null) {
                    driver.println("source select=path not supported by this test driver");
                    environment.usable = false;
                }
                if (".".equals(role)) {
                    if (href == null) {
                        environment.streamedContent = xpc.evaluate("string(content)", source).toString();
                    } else {
                        environment.streamedFile = new File(((XdmNode) env).getBaseURI().resolve(href));
                        res.file = environment.streamedFile;
                    }
                    environment.streamedInputValidation = validation;
                } else {
                    File file = new File(((XdmNode) env).getBaseURI().resolve(href));
                    environment.streamedSecondaryDocs.put(file.toURI().toString(), validation);
                    res.file = file;
                }
            } else {
                Source ss;
                File file = null;
                FileInputStream fileInputStream = null;
                StringReader stringReader = null;
                String baseUri = null;
                if (href != null) {
                    URI fileLoc = env.getBaseURI().resolve(href);
                    baseUri = fileLoc.toString();
                    if (fileLoc.getScheme().equals("file")) {
                        file = new File(env.getBaseURI().resolve(href));
                        if (res.uri == null) {
                            res.uri = file.toURI().toString();
                        }
                        res.file = file;
                    }
                    try {
                        if (file == null) {
                            ss = new StreamSource(fileLoc.toString());
                        } else {
                            fileInputStream = new FileInputStream(file);
                            ss = new StreamSource(fileInputStream, res.uri);
                        }
                        if ("true".equals(xinc)) {
                            AugmentedSource as = AugmentedSource.makeAugmentedSource(ss);
                            as.setXIncludeAware(true);
                            ss = as;
                        }
                    } catch (FileNotFoundException e) {
                        driver.println("*** failed to find source document " + href);
                        continue;
                    }
                } else {
                    // content is inline in the catalog
                    baseUri = res.uri;
                    if (res.uri == null) {
                        baseUri = env.getBaseURI().toString();
                    }
                    String content = xpc.evaluate("string(content)", source).toString();
                    stringReader = new StringReader(content);
                    ss = new StreamSource(stringReader, baseUri);
                }

                if (selectedTreeModel.getName().equals("DOM") || selectedTreeModel.getName().equals("DOMINO")) {
                    // create the DOM directly. Using a SAX parser plus document builder loses
                    // entities and doctype nodes, which will be encountered in real life
                    Document doc = null;
                    try {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setNamespaceAware(true);
                        javax.xml.parsers.DocumentBuilder db = factory.newDocumentBuilder();
                        if (file != null) {
                            doc = db.parse(file);
                        } else if (fileInputStream != null) {
                            InputSource is = new InputSource(fileInputStream);
                            is.setSystemId(baseUri);
                            doc = db.parse(is);
                        } else if (stringReader != null) {
                            InputSource is = new InputSource(stringReader);
                            is.setSystemId(baseUri);
                            doc = db.parse(is);
                        }
                    } catch (ParserConfigurationException | IOException | SAXException e) {
                        e.printStackTrace();
                    }

                    if (selectedTreeModel.getName().equals("DOM")) {
                        NodeInfo root = new DocumentWrapper(doc, res.uri, environment.processor.getUnderlyingConfiguration()).getRootNode();
                        res.doc = (XdmNode)XdmValue.wrap(root);
                    } else {
                        res.doc = driver.makeDominoTree(doc, environment.processor.getUnderlyingConfiguration(), res.uri);
                    }
                    environment.sourceDocs.put(res.uri, res.doc);
                } else {
                    try {
                        builder.setLineNumbering(true);
                        res.doc = builder.build(ss);
                        environment.sourceDocs.put(res.uri, res.doc);
                        environment.sourceDocs.put(rawUri, res.doc);

                    } catch (SaxonApiException e) {
                        //e.printStackTrace();
                        driver.println("*** failed to build source document " + href + " - " + e.getMessage());
                    }
                }

                builder.setTreeModel(selectedTreeModel);

                XdmItem selectedItem = res.doc;
                if (select != null) {
                    XPathSelector selector = environment.xpathCompiler.compile(select).load();
                    selector.setContextItem(selectedItem);
                    selectedItem = selector.evaluateSingle();
                }


                if (role != null) {
                    if (".".equals(role)) {
                        environment.contextItem = selectedItem;
                    } else if (role.startsWith("$")) {
                        String varName = role.substring(1);
                        environment.params.put(new QName(varName), selectedItem);
                        environment.xpathCompiler.declareVariable(new QName(varName));
                        environment.paramDeclarations.append("declare variable $" + varName + " external; ");
                    }
                }
            }
            String definesStylesheet = ((XdmNode) source).attribute("defines-stylesheet");
            if (definesStylesheet != null) {
                definesStylesheet = definesStylesheet.trim();
            }
            if (("true".equals(definesStylesheet) || "1".equals(definesStylesheet)) && !driver.runWithJS) {
                // try using an embedded stylesheet from the source document
                try {
                    Source styleSource = environment.xsltCompiler.getAssociatedStylesheet(
                            ((XdmNode) environment.contextItem).asSource(), null, null, null);
                    environment.xsltExecutable = environment.xsltCompiler.compile(styleSource);
                } catch (SaxonApiException e) {
                    driver.println("*** failed to compile stylesheet referenced in source document " + href);
                }
            }
        }
    }

    /**
     * The environment acts as a URIResolver
     */

    public Source resolve(String href, String base) throws TransformerException {
        XdmNode node = sourceDocs.get(href);
        if (node == null) {
            String uri;
            if (base == null) {
                base = href;
                href = "";
            }
            try {
                uri = new URI(base).resolve(href).toString();
            } catch (URISyntaxException | IllegalArgumentException e) {
                uri = href;
            }
            String val = streamedSecondaryDocs.get(uri);
            if (val != null) {
                Source source = new StreamSource(uri);
                if (!val.equals("skip")) {
                    source = AugmentedSource.makeAugmentedSource(source);
                    ((AugmentedSource) source).setSchemaValidationMode(Validation.getCode(val));
                }
                return source;
            } else {
                return null;
            }
        } else {
            return node.asSource();
        }
    }

    public CatalogResource getContextDocument() {
        return catalogResources.get("");
    }


    public abstract static class ResetAction {
        public abstract void reset(Environment env);
    }


}

