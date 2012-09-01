/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.camel.maven;

import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.Logger;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.log.Log4JLogChute;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.codehaus.jackson.map.ObjectMapper;
import org.fusesource.camel.component.salesforce.api.DefaultRestClient;
import org.fusesource.camel.component.salesforce.api.RestClient;
import org.fusesource.camel.component.salesforce.api.RestException;
import org.fusesource.camel.component.salesforce.api.SalesforceSession;
import org.fusesource.camel.component.salesforce.api.dto.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Goal which generates POJOs for Salesforce SObjects
 *
 * @goal generate
 * 
 * @phase generate-sources
 *
 */
public class CamelSalesforceMojo extends AbstractMojo
{
    private static final String JAVA_EXT = ".java";
    private static final String PACKAGE_NAME_PATTERN = "^[a-z]+(\\.[a-z][a-z0-9]*)*$";
    private static final String SOBJECT_POJO_VM = "/sobject-pojo.vm";
    private static final String SOBJECT_QUERY_RECORDS_VM = "/sobject-query-records.vm";

    // used for velocity logging, to avoid creating velocity.log
    private static final Logger LOG = Logger.getLogger(CamelSalesforceMojo.class.getName());

    /**
     * Salesforce client id
     * @parameter expression="${clientId}"
     * @required
     */
    protected String clientId;

    /**
     * Salesforce client secret
     * @parameter expression="${clientSecret}"
     * @required
     */
    protected String clientSecret;

    /**
     * Salesforce user name
     * @parameter expression="${userName}"
     * @required
     */
    protected String userName;

    /**
     * Salesforce password
     * @parameter expression="${password}"
     * @required
     */
    protected String password;

    /**
     * Salesforce version
     * @parameter expression="${version}" default-value="25.0"
     */
    protected String version;

    /**
     * Location of the file.
     * @parameter expression="${outputDirectory}" default-value="${project.build.directory}/generated-sources/camel-salesforce"
     * @required
     */
    protected File outputDirectory;

    /**
     * Names of Salesforce SObject for which POJOs must be generated
     * @parameter
     */
    protected String[] includes;

    /**
     * Do NOT generate POJOs for these Salesforce SObjects
     * @parameter
     */
    protected String[] excludes;

    /**
     * Include Salesforce SObjects that match pattern
     * @parameter expression="${includePattern}"
     */
    protected String includePattern;

    /**
     * Exclude Salesforce SObjects that match pattern
     * @parameter expression="${excludePattern}"
     */
    protected String excludePattern;

    /**
     * Java package name for generated POJOs
     * @parameter expression="${packageName}" default-value="org.fusesource.camel.salesforce.dto"
     */
    protected String packageName;

    private VelocityEngine engine;

    /**
     * Execute the mojo to generate SObject POJOs
     * @throws MojoExecutionException
     */
    public void execute()
        throws MojoExecutionException
    {
        // initialize velocity to load resources from class loader and use Log4J
        Properties velocityProperties = new Properties();
        velocityProperties.setProperty(RuntimeConstants.RESOURCE_LOADER, "cloader");
        velocityProperties.setProperty("cloader.resource.loader.class", ClasspathResourceLoader.class.getName());
        velocityProperties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS, Log4JLogChute.class.getName());
        velocityProperties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM + ".log4j.logger", getClass().getName());
        engine = new VelocityEngine(velocityProperties);
        engine.init();

        // make sure we can load both templates
        if (!engine.resourceExists(SOBJECT_POJO_VM) ||
            !engine.resourceExists(SOBJECT_QUERY_RECORDS_VM)) {
            throw new MojoExecutionException("Velocity templates not found");
        }

        // connect to Salesforce
        final DefaultHttpClient httpClient = new DefaultHttpClient();
        final SalesforceSession session = new SalesforceSession(httpClient,
            clientId, clientSecret, userName, password);
        getLog().info("Salesforce login...");
        try {
            session.login(null);
        } catch (RestException e) {
            String msg = "Salesforce login error " + e.getMessage();
            getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        }
        getLog().info("Salesforce login successful");

        // create rest client
        final RestClient restClient = new DefaultRestClient(httpClient,
            version, "json", session);

        // use Jackson json
        final ObjectMapper mapper = new ObjectMapper();

        // call getGlobalObjects to get all SObjects
        final Set<String> objectNames = new HashSet<String>();
        try {
            getLog().info("Getting Salesforce Objects...");
            final GlobalObjects globalObjects = mapper.readValue(restClient.getGlobalObjects(),
                GlobalObjects.class);

            // create a list of object names
            for (SObject sObject : globalObjects.getSobjects()) {
                objectNames.add(sObject.getName());
            }
        } catch (Exception e) {
            String msg = "Error getting global Objects " + e.getMessage();
            getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        }

        // check if we are generating POJOs for all objects or not
        if ((includes != null && includes.length > 0) ||
            (excludes != null && excludes.length > 0) ||
            (includePattern != null && !includePattern.trim().isEmpty()) ||
            (excludePattern != null && !excludePattern.trim().isEmpty())) {

            getLog().info("Looking for matching Object names...");
            // create a list of accepted names
            final Set<String> includedNames = new HashSet<String>();
            if (includes != null && includes.length > 0) {
                for (String name : includes) {
                    name = name.trim();
                    if (name.isEmpty()) {
                        throw new MojoExecutionException("Invalid empty name in includes");
                    }
                    includedNames.add(name);
                }
            }

            final Set<String> excludedNames = new HashSet<String>();
            if (excludes != null && excludes.length > 0) {
                for (String name : excludes) {
                    name = name.trim();
                    if (name.isEmpty()) {
                        throw new MojoExecutionException("Invalid empty name in excludes");
                    }
                    excludedNames.add(name);
                }
            }

            // default is to include everything
            final Pattern incPattern = Pattern.compile(includePattern != null && !includePattern.trim().isEmpty() ?
                includePattern.trim() : ".*");

            // default is to exclude empty names
            final Pattern excPattern = Pattern.compile(excludePattern != null && excludePattern.trim().isEmpty() ?
                excludePattern.trim() : "^$");

            Set<String> acceptedNames = new HashSet<String>();
            for (String name : objectNames) {
                if (includedNames.contains(name) ||
                    (!excludedNames.contains(name) &&
                    incPattern.matcher(name).matches() &&
                    !excPattern.matcher(name).matches())
                    ) {
                    acceptedNames.add(name);
                }
            }
            objectNames.clear();
            objectNames.addAll(acceptedNames);

            getLog().info("Found " + objectNames.size() + " matching Objects");

        } else {
            getLog().warn("Generating Java classes for all " + objectNames.size() + " Objects, this may take a while...");
        }

        // for every accepted name, get SObject description
        final Set<SObjectDescription> descriptions =
            new HashSet<SObjectDescription>();

        try {
            getLog().info("Retrieving Object descriptions...");
            for (String name : objectNames) {
                descriptions.add(mapper.readValue(restClient.getSObjectDescription(name),
                        SObjectDescription.class));
            }
        } catch (Exception e) {
            String msg = "Error getting SObject description " + e.getMessage();
            getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        }

        // create package directory
        // validate package name
        if (!packageName.matches(PACKAGE_NAME_PATTERN)) {
            throw new MojoExecutionException("Invalid package name " + packageName);
        }
        final File pkgDir = new File(outputDirectory, packageName.trim().replace('.', File.separatorChar));
        if (!pkgDir.exists()) {
            if (!pkgDir.mkdirs()) {
                throw new MojoExecutionException("Unable to create " + pkgDir);
            }
        }

        getLog().info("Generating Java Classes...");
        // generate POJOs for every object description
        final GeneratorUtility utility = new GeneratorUtility();
        // should we provide a flag to control timestamp generation?
        final String generatedDate = new Date().toString();
        for (SObjectDescription description : descriptions) {
            processDescription(pkgDir, description, utility, generatedDate);
        }

        getLog().info("Successfully generated " + (descriptions.size() * 2) + " Java Classes");
    }

    private void processDescription(File pkgDir, SObjectDescription description, GeneratorUtility utility, String generatedDate) throws MojoExecutionException {
        // generate a source file for SObject
        String fileName = description.getName() + JAVA_EXT;
        BufferedWriter writer = null;
        try {
            File pojoFile = new File(pkgDir, fileName);
            writer = new BufferedWriter(new FileWriter(pojoFile));

            VelocityContext context = new VelocityContext();
            context.put("packageName", packageName);
            context.put("utility", utility);
            context.put("desc", description);
            context.put("generatedDate", generatedDate);

            Template pojoTemplate = engine.getTemplate(SOBJECT_POJO_VM);
            pojoTemplate.merge(context, writer);

        } catch (IOException e) {
            String msg = "Error creating " + fileName + ": " + e.getMessage();
            getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        // write the QueryRecords class
        fileName = "QueryRecords" + fileName;
        try {
            File queryFile = new File(pkgDir, fileName);
            writer = new BufferedWriter(new FileWriter(queryFile));

            VelocityContext context = new VelocityContext();
            context.put("packageName", packageName);
            context.put("desc", description);
            context.put("generatedDate", generatedDate);

            Template queryTemplate = engine.getTemplate(SOBJECT_QUERY_RECORDS_VM);
            queryTemplate.merge(context, writer);

        } catch (IOException e) {
            String msg = "Error creating " + fileName + ": " + e.getMessage();
            getLog().error(msg, e);
            throw new MojoExecutionException(msg, e);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public static class GeneratorUtility {

        private final Set<String> baseFields;
        private final Map<String, String> lookupMap;

        public GeneratorUtility() {
            baseFields = new HashSet<String>();
            for (Field field : AbstractSObjectBase.class.getDeclaredFields()) {
                baseFields.add(field.getName());
            }

            // create a type map
            // using JAXB mapping, for the most part
            // TODO add support for commented types like dates
            final String[][] typeMap = new String[][] {
                {"ID", "String"}, // mapping for tns:ID SOAP type
                {"string", "String"},
                {"integer", "java.math.BigInteger"},
                {"int", "Integer"},
                {"long", "Long"},
                {"short", "Short"},
                {"decimal", "java.math.BigDecimal"},
                {"float", "Float"},
                {"double", "Double"},
                {"boolean", "Boolean"},
                {"byte", "Byte"},
//                {"QName", "javax.xml.namespace.QName"},
//                {"dateTime", "javax.xml.datatype.XMLGregorianCalendar"},
                {"dateTime", "String"},
//                {"base64Binary", "byte[]"},
//                {"hexBinary", "byte[]"},
                {"unsignedInt", "Long"},
                {"unsignedShort", "Integer"},
                {"unsignedByte", "Short"},
//                {"time", "javax.xml.datatype.XMLGregorianCalendar"},
                {"time", "String"},
//                {"date", "javax.xml.datatype.XMLGregorianCalendar"},
                {"date", "String"},
//                {"g", "javax.xml.datatype.XMLGregorianCalendar"},
                {"g", "String"},
/*
                {"anySimpleType", "java.lang.Object"},
                {"anySimpleType", "java.lang.String"},
                {"duration", "javax.xml.datatype.Duration"},
                {"NOTATION", "javax.xml.namespace.QName"}
*/
            };
            lookupMap = new HashMap<String, String>();
            for (String[] entry : typeMap) {
                lookupMap.put(entry[0], entry[1]);
            }
        }

        public boolean notBaseField(String name) {
            return !baseFields.contains(name);
        }

        public String getFieldType(SObjectField field) throws MojoExecutionException {
            // map field to Java type
            final String soapType = field.getSoapType();
            final String type = lookupMap.get(soapType.substring(soapType.indexOf(':')+1));
            if (type == null) {
                String msg = String.format("Unsupported type %s for field %s", soapType, field.getName());
                throw new MojoExecutionException(msg);
            }
            return type;
        }
    }

}
