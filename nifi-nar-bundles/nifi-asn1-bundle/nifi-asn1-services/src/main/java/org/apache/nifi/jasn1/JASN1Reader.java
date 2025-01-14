/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.jasn1;

import antlr.RecognitionException;
import antlr.TokenStreamException;
import com.beanit.asn1bean.compiler.BerClassWriter;
import com.beanit.asn1bean.compiler.BerClassWriterFactory;
import com.beanit.asn1bean.compiler.model.AsnModel;
import com.beanit.asn1bean.compiler.model.AsnModule;
import com.beanit.asn1bean.compiler.parser.ASNLexer;
import com.beanit.asn1bean.compiler.parser.ASNParser;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.AbstractConfigurableComponent;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.jasn1.preprocess.AsnPreprocessorEngine;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.util.file.classloader.ClassLoaderUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Tags({"asn", "ans1", "jasn.1", "jasn1", "record", "reader", "parser"})
@CapabilityDescription("Reads ASN.1 content and creates NiFi records.")
public class JASN1Reader extends AbstractConfigurableComponent implements RecordReaderFactory {

    private static final PropertyDescriptor ROOT_MODEL_NAME = new PropertyDescriptor.Builder()
        .name("root-model-name")
        .displayName("Root Model Name")
        .description("The model name in the form of 'MODULE-NAME.ModelType'. " +
            "Mutually exclusive with and should be preferred to 'Root Model Class Name'. (See additional details for more information.)")
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .required(false)
        .build();

    private static final PropertyDescriptor ROOT_CLASS_NAME = new PropertyDescriptor.Builder()
        .name("root-model-class-name")
        .displayName("Root Model Class Name")
        .description("A canonical class name that is generated by the ASN.1 compiler to encode the ASN.1 input data. Mutually exclusive with 'Root Model Name'." +
            " Should be used when the former cannot be set properly. See additional details for more information.")
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .required(false)
        .build();

    /**
     * Not included!
     * To make this service as simple as possible, records are to be expected to correspond to a concrete ASN type.
     * Not removing though, should it be required in the future.
     */
    private static final PropertyDescriptor RECORD_FIELD = new PropertyDescriptor.Builder()
        .name("record-field")
        .displayName("Record Field")
        .description("Optional field name pointing an instance field containing record elements.")
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .required(false)
        .build();


    static final PropertyDescriptor ASN_FILES = new PropertyDescriptor.Builder()
        .name("asn-files")
        .displayName("ASN.1 Files")
        .description("Comma-separated list of ASN.1 files.")
        .required(false)
        .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    /**
     * Not included!
     * To make this service as simple as possible, classpath modification is not supported currently as it's
     * benefit would be questionable.
     * Not removing though, should it be required in the future.
     */
    private static final PropertyDescriptor ITERATOR_PROVIDER_CLASS_NAME = new PropertyDescriptor.Builder()
        .name("iterator-provider-class-name")
        .displayName("Iterator Provider Class Name")
        .description("A canonical class name implementing record iteration logic.")
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .required(false)
        .build();

    private static final AllowableValue DEFAULT = new AllowableValue(
            "DEFAULT",
            "Default",
            "No additional preprocessing should occur, use original schema."
    );

    private static final AllowableValue ADDITIONAL_PREPROCESSING = new AllowableValue(
            "ADDITIONAL_PREPROCESSING",
            "Additional Preprocessing",
            "Perform additional preprocessing, resulting in potentially modified schema. (See additional details for more information.)"
    );

    private static final PropertyDescriptor SCHEMA_PREPARATION_STRATEGY = new PropertyDescriptor.Builder()
        .name("Schema Preparation Strategy")
        .description("When set, NiFi will do additional preprocessing steps that creates modified versions of the provided ASN files," +
                " removing unsupported features in a way that makes them less strict but otherwise should still be compatible with incoming data." +
                " The original files will remain intact and new ones will be created with the same names in the directory defined in the 'Additional Preprocessing Output Directory' property." +
                " For more information about these additional preprocessing steps please see Additional Details - Additional Preprocessing.")
        .allowableValues(DEFAULT, ADDITIONAL_PREPROCESSING)
        .required(true)
        .defaultValue(DEFAULT.getValue())
        .build();

    private static final PropertyDescriptor SCHEMA_PREPARATION_DIRECTORY = new PropertyDescriptor.Builder()
        .name("Schema Preparation Directory")
        .description("When the processor is configured to do additional preprocessing, new modified schema files will be created in this directory." +
                " For more information about additional preprocessing please see description of the 'Do Additional Preprocessing' property or Additional Details - Additional Preprocessing.")
        .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
        .addValidator(StandardValidators.createDirectoryExistsValidator(true, false))
        .dependsOn(SCHEMA_PREPARATION_STRATEGY, ADDITIONAL_PREPROCESSING)
        .required(true)
        .build();

    private final List<PropertyDescriptor> propertyDescriptors = Arrays.asList(
        ROOT_MODEL_NAME,
        ROOT_CLASS_NAME,
        ASN_FILES,
        SCHEMA_PREPARATION_STRATEGY,
        SCHEMA_PREPARATION_DIRECTORY
    );

    private String identifier;
    ComponentLog logger;

    private final RecordSchemaProvider schemaProvider = new RecordSchemaProvider();

    volatile Path asnOutDir;
    private volatile PropertyValue rootModelNameProperty;
    private volatile PropertyValue rootClassNameProperty;
    private volatile PropertyValue recordFieldProperty;
    private volatile PropertyValue iteratorProviderProperty;

    volatile ClassLoader customClassLoader;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @Override
    public void initialize(ControllerServiceInitializationContext context) {
        identifier = context.getIdentifier();
        logger = context.getLogger();
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>(super.customValidate(validationContext));

        PropertyValue rootModelNameProperty = validationContext.getProperty(ROOT_MODEL_NAME);
        PropertyValue rootClassNameProperty = validationContext.getProperty(ROOT_CLASS_NAME);

        if (rootModelNameProperty.isSet() && rootClassNameProperty.isSet()) {
            results.add(new ValidationResult.Builder()
                .subject(ROOT_MODEL_NAME.getName())
                .valid(false)
                .explanation("Only one of '" + ROOT_MODEL_NAME.getDisplayName() + "' or '" + ROOT_CLASS_NAME.getDisplayName() + "' should be set!")
                .build());
        }

        if (!rootModelNameProperty.isSet() && !rootClassNameProperty.isSet()) {
            results.add(new ValidationResult.Builder()
                .subject(ROOT_MODEL_NAME.getName())
                .valid(false)
                .explanation("Either '" + ROOT_MODEL_NAME.getDisplayName() + "' or '" + ROOT_CLASS_NAME.getDisplayName() + "' should be set!")
                .build());
        }

        return results;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        if (context.getProperty(ASN_FILES) != null && context.getProperty(ASN_FILES).isSet()) {
            String asnFilesString = context.getProperty(ASN_FILES).evaluateAttributeExpressions().getValue();

            if (ADDITIONAL_PREPROCESSING.getValue().equals(context.getProperty(SCHEMA_PREPARATION_STRATEGY).getValue())) {
                final AsnPreprocessorEngine asnPreprocessorEngine = new AsnPreprocessorEngine();

                final String preprocessOutputDirectory = context.getProperty(SCHEMA_PREPARATION_DIRECTORY).evaluateAttributeExpressions().getValue();

                asnFilesString = asnPreprocessorEngine.preprocess(
                        logger,
                        asnFilesString,
                        preprocessOutputDirectory
                );
            }

            final String[] asnFilesPaths = Arrays.stream(asnFilesString.split(","))
                    .map(String::trim)
                    .toArray(String[]::new);
            compileAsnToClass(asnFilesPaths);
        }

        try {
            if (asnOutDir != null) {
                customClassLoader = ClassLoaderUtils.getCustomClassLoader(
                    asnOutDir.toString(),
                    this.getClass().getClassLoader(),
                    null
                );
            } else {
                customClassLoader = this.getClass().getClassLoader();
            }
        } catch (final Exception ex) {
            logger.error("Could not create ClassLoader for compiled ASN.1 classes", ex);
        }

        rootModelNameProperty = context.getProperty(ROOT_MODEL_NAME);
        rootClassNameProperty = context.getProperty(ROOT_CLASS_NAME);
        recordFieldProperty = context.getProperty(RECORD_FIELD);
        iteratorProviderProperty = context.getProperty(ITERATOR_PROVIDER_CLASS_NAME);
    }

    private void compileAsnToClass(String... asnFilePaths) {
        try {
            asnOutDir = Files.createTempDirectory(getIdentifier() + "_asn_");
        } catch (IOException e) {
            throw new ProcessException("Could not create temporary directory for compiled ASN.1 files", e);
        }

        HashMap<String, AsnModule> modulesByName = new HashMap<>();

        Exception parseException = null;
        for (String asn1File : asnFilePaths) {
            logger.info("Parsing " + asn1File);
            try {
                AsnModel model = getJavaModelFromAsn1File(asn1File);
                modulesByName.putAll(model.modulesByName);
            } catch (FileNotFoundException e) {
                logger.error("ASN.1 file not found [{}]", asn1File, e);
                parseException = e;
            } catch (TokenStreamException | RecognitionException e) {
                logger.error("ASN.1 stream parsing failed [{}]", asn1File, e);
                parseException = e;
            } catch (Exception e) {
                logger.error("ASN.1 parsing failed [{}]", asn1File, e);
                parseException = e;
            }
        }

        if (parseException != null) {
            throw new ProcessException("ASN.1 parsing failed", parseException);
        }

        try {
            logger.info("Writing ASN.1 classes to directory [{}]", asnOutDir);

            BerClassWriter classWriter = BerClassWriterFactory.createBerClassWriter(modulesByName, asnOutDir);

            classWriter.translate();
        } catch (Exception e) {
            throw new ProcessException("ASN.1 compilation failed", e);
        }

        List<File> javaFiles;
        try {
            javaFiles = Files.walk(asnOutDir)
                .filter(Files::isRegularFile)
                .map(Object::toString)
                .filter(filePath -> filePath.endsWith(".java"))
                .map(File::new)
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new ProcessException("Access directory failed " + asnOutDir);
        }

        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(null, null, null);

        List<String> optionList = new ArrayList<>();
        optionList.addAll(Arrays.asList("-classpath", com.beanit.asn1bean.ber.types.BerType.class.getProtectionDomain().getCodeSource().getLocation().getFile()));

        Iterable<? extends JavaFileObject> units;
        units = fileManager.getJavaFileObjectsFromFiles(javaFiles);

        DiagnosticCollector<JavaFileObject> diagnosticListener = new DiagnosticCollector<>();
        JavaCompiler.CompilationTask task = javaCompiler.getTask(null, fileManager, diagnosticListener, optionList, null, units);

        Boolean success = task.call();
        if (!success) {
            Set<String> errorMessages = new LinkedHashSet<>();
            diagnosticListener.getDiagnostics().stream().map(d -> d.getMessage(Locale.getDefault())).forEach(errorMessages::add);

            errorMessages.forEach(logger::error);

            throw new ProcessException("ASN.1 Java compilation failed");
        }
    }

    @OnDisabled
    public void onDisabled() {
        deleteAsnOutDir();
    }

    void deleteAsnOutDir() {
        if (asnOutDir != null) {
            try {
                Files.walk(asnOutDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            } catch (IOException e) {
                throw new ProcessException("Delete directory failed " + asnOutDir);
            }
        }
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public RecordReader createRecordReader(
        Map<String, String> variables,
        InputStream in,
        long inputLength,
        ComponentLog logger
    ) {
        final String rootClassName;
        if (rootModelNameProperty != null && rootModelNameProperty.isSet()) {
            rootClassName = guessRootClassName(rootModelNameProperty.evaluateAttributeExpressions(variables).getValue());
        } else {
            rootClassName = rootClassNameProperty.evaluateAttributeExpressions(variables).getValue();
        }
        final String recordField = recordFieldProperty.evaluateAttributeExpressions(variables).getValue();
        final String iteratorProviderClassName = iteratorProviderProperty.evaluateAttributeExpressions(variables).getValue();
        return new JASN1RecordReader(rootClassName, recordField, schemaProvider, customClassLoader, iteratorProviderClassName, in, logger);
    }

    AsnModel getJavaModelFromAsn1File(String inputFileName)
            throws FileNotFoundException, TokenStreamException, RecognitionException {

        InputStream stream = new FileInputStream(inputFileName);
        ASNLexer lexer = new ASNLexer(stream);

        AtomicBoolean parseError = new AtomicBoolean(false);
        ASNParser parser = new ASNParser(lexer) {
            @Override
            public void reportError(String s) {
                logger.error("{} - {}", inputFileName, s);
                parseError.set(true);
            }

            @Override
            public void reportError(RecognitionException e) {
                logger.error("{} - {}", inputFileName, e.toString());
                parseError.set(true);
            }
        };

        AsnModel model = new AsnModel();
        parser.module_definitions(model);

        if (parseError.get()) {
            throw new ProcessException("ASN.1 parsing failed");
        }

        return model;
    }

    String guessRootClassName(String rootModelName) {
        try {
            StringBuilder rootClassNameBuilder = new StringBuilder();

            int moduleTypeDelimiterIndex = rootModelName.lastIndexOf(".");

            String moduleName = rootModelName.substring(0, moduleTypeDelimiterIndex);
            String typeName = rootModelName.substring(moduleTypeDelimiterIndex);

            rootClassNameBuilder.append(moduleName.replaceAll("-", ".").toLowerCase());
            rootClassNameBuilder.append(typeName);

            return rootClassNameBuilder.toString();
        } catch (Exception e) {
            throw new ProcessException("Could not infer root model name from '" + rootModelName + "'", e);
        }
    }
}
