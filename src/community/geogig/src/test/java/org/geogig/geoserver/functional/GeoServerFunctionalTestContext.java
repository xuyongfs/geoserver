/* (c) 2016 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geogig.geoserver.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Random;
import java.util.Set;

import javax.json.JsonObject;

import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.geogig.geoserver.GeoGigTestData;
import org.geogig.geoserver.GeoGigTestData.CatalogBuilder;
import org.geogig.geoserver.config.RepositoryInfo;
import org.geogig.geoserver.config.RepositoryManager;
import org.geogig.geoserver.rest.GeoServerRepositoryProvider;
import org.geogig.web.functional.FunctionalTestContext;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.test.GeoServerSystemTestSupport;
import org.geoserver.test.TestSetupFrequency;
import org.geotools.data.DataAccess;
import org.locationtech.geogig.geotools.data.GeoGigDataStore;
import org.locationtech.geogig.geotools.data.GeoGigDataStoreFactory;
import org.locationtech.geogig.repository.GeoGIG;
import org.locationtech.geogig.repository.Repository;
import org.locationtech.geogig.repository.RepositoryResolver;
import org.locationtech.geogig.web.api.TestData;
import org.opengis.feature.Feature;
import org.opengis.feature.type.FeatureType;
import org.restlet.data.Form;
import org.restlet.data.Method;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.Document;

import com.google.common.base.Throwables;
import com.google.common.collect.Sets;


/**
 * Context for running GeoGIG web API functional tests from the plugin endpoints.
 */
public class GeoServerFunctionalTestContext extends FunctionalTestContext {

    private static final Random rnd = new Random();

    private MockHttpServletResponse lastResponse = null;

    private GeoServerRepositoryProvider repoProvider = null;

    /**
     * Helper class for running mock http requests.
     */
    private class TestHelper extends GeoServerSystemTestSupport {

        public TestHelper() {
            super();
            testSetupFrequency = TestSetupFrequency.REPEAT;
        }

        /**
         * Override to avoid creating default geoserver test data
         */
        @Override
        protected void setUpTestData(SystemTestData testData) throws Exception {
        }

        /**
         * @return the catalog used by the test helper
         */
        public Catalog getCatalog() {
            return super.getCatalog();
        }

        /**
         * Issue a POST request to the provided URL with the given file passed as form data.
         *
         * @param resourceUri   the url to issue the request to
         * @param formFieldName the form field name for the file to be posted
         * @param file          the file to post
         *
         * @return the response to the request
         */
        public MockHttpServletResponse postFile(String resourceUri, String formFieldName, File file)
                throws Exception {
            Part[] parts = new Part[1];
            parts[0] = new FilePart(formFieldName, file);

            MultipartRequestEntity multipart = new MultipartRequestEntity(parts,
                    new PostMethod().getParams());

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            multipart.writeRequest(bout);

            MockHttpServletRequest req = createRequest(resourceUri);
            req.setContentType(multipart.getContentType());
            req.addHeader("Content-Type", multipart.getContentType());
            req.setMethod("POST");
            req.setContent(bout.toByteArray());

            return dispatch(req);
        }

        /**
         * Issue a request with the given {@link Method} to the provided resource URI.
         *
         * @param method      the http method to use
         * @param resourceUri the uri to issue the request to
         *
         * @return the response to the request
         */
        public MockHttpServletResponse callInternal(Method method, String resourceUri)
                throws Exception {
            MockHttpServletRequest request = super.createRequest(resourceUri);
            request.setMethod(method.getName());

            return dispatch(request, null);

        }

        public MockHttpServletResponse callInternal(Method method, String resourceUri,
                JsonObject payload) throws Exception {
            return callWithContentTypeInternal(method, resourceUri, payload, "application/json");
        }

        public MockHttpServletResponse callWithContentTypeInternal(Method method, String resourceUri,
                JsonObject payload, String contentType) throws Exception {
            MockHttpServletRequest request = super.createRequest(resourceUri);
            request.setMethod(method.getName());
            // set the JSON payload
            request.setContent(payload.toString().getBytes());
            request.setContentType(contentType);

            return dispatch(request, null);
        }

        public MockHttpServletResponse callInternal(Method method, String resourceUri,
                Form form) throws Exception {
            MockHttpServletRequest request = super.createRequest(resourceUri);
            request.setMethod(method.getName());
            // set the JSON payload
            request.setContent(form.encode().getBytes());
            request.setContentType("application/x-www-form-urlencoded");

            return dispatch(request, null);
        }

        /**
         * Provide access to the helper function that turns the response into a {@link Document}.
         *
         * @param stream the stream to read as a document
         *
         * @return the {@link Document}
         *
         * @throws Exception
         */
        public Document getDom(InputStream stream) throws Exception {
            return dom(stream);
        }
    }

    private TestHelper helper = null;

    /**
     * Set up the context for a scenario.
     */
    @Override
    protected void setUp() throws Exception {
        if (helper == null) {
            helper = new TestHelper();
            helper.doSetup();

            repoProvider = new GeoServerRepositoryProvider();

            RepositoryManager.get().setCatalog(helper.getCatalog());
        }

    }

    /**
     * Clean up resources used in the scenario.
     */
    @Override
    protected void tearDown() throws Exception {
        try {
            if (helper != null) {
                RepositoryManager.close();
                helper.doTearDown();
            }
        } finally {
            helper = null;
        }
    }

    /**
     * Return the {@link GeoGIG} that corresponds to the given repository name.
     *
     * @param name the repository to get
     *
     * @return the repository
     */
    @Override
    public Repository getRepo(String name) {
        return repoProvider.getGeogig(name).orNull();
    }

    /**
     * Create a repository with the given name for testing.
     *
     * @param name the repository name
     *
     * @return a newly created {@link TestData} for the repository.
     *
     * @throws Exception
     */
    @Override
    protected TestData createRepo(String name) throws Exception {
        GeoGigTestData testData = new GeoGigTestData(RunWebAPIFunctionalTest.TEMP_ROOT.getRoot());
        testData.setUp(name);
        testData.init().config("user.name", "John").config("user.email", "John.Doe@example.com");
        GeoGIG geogig = testData.getGeogig();

        Catalog catalog = helper.getCatalog();
        CatalogBuilder catalogBuilder = testData.newCatalogBuilder(catalog);
        int i = rnd.nextInt();
        catalogBuilder.namespace("geogig.org/" + i).workspace("geogigws" + i)
                .store("geogigstore" + i);
        catalogBuilder.addAllRepoLayers().build();

        String workspaceName = catalogBuilder.workspaceName();
        String storeName = catalogBuilder.storeName();
        DataStoreInfo dsInfo = catalog.getDataStoreByName(workspaceName, storeName);
        assertNotNull(dsInfo);
        assertEquals(GeoGigDataStoreFactory.DISPLAY_NAME, dsInfo.getType());
        DataAccess<? extends FeatureType, ? extends Feature> dataStore = dsInfo.getDataStore(null);
        assertNotNull(dataStore);
        assertTrue(dataStore instanceof GeoGigDataStore);

        String repoStr = (String) dsInfo.getConnectionParameters()
                .get(GeoGigDataStoreFactory.REPOSITORY.key);
        // resolve the repo
        URI repoURI = new URI(repoStr);
        RepositoryResolver resolver = RepositoryResolver.lookup(repoURI);
        String repoName = resolver.getName(repoURI);
        RepositoryInfo repositoryInfo = RepositoryManager.get().getByRepoName(repoName);
        assertNotNull(repositoryInfo);
        return new TestData(geogig);
    }

    /**
     * Helper function that asserts that there is a last response and returns it.
     *
     * @return the last response
     */
    private MockHttpServletResponse getLastResponse() {
        assertNotNull(lastResponse);
        return lastResponse;
    }

    /**
     * Issue a POST request to the provided URL with the given file passed as form data.
     *
     * @param resourceUri   the url to issue the request to
     * @param formFieldName the form field name for the file to be posted
     * @param file          the file to post
     */
    @Override
    protected void postFileInternal(String resourceUri, String formFieldName, File file) {
        resourceUri = replaceVariables(resourceUri);
        try {
            lastResponse = helper.postFile("/geogig" + resourceUri, formFieldName, file);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }

    /**
     * Issue a request with the given {@link Method} to the provided resource URI.
     *
     * @param method      the http method to use
     * @param resourceUri the uri to issue the request to
     */
    @Override
    protected void callInternal(Method method, String resourceUri) {
        try {
            resourceUri = replaceVariables(resourceUri);
            this.lastResponse = helper.callInternal(method, "/geogig" + resourceUri);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }

    /**
     * @return the content of the last response as text
     */
    @Override
    public String getLastResponseText() {
        try {
            return getLastResponse().getContentAsString();
        } catch (UnsupportedEncodingException ex) {
            Throwables.propagate(ex);
        }
        return null;
    }

    /**
     * @return the content type of the last response
     */
    @Override
    public String getLastResponseContentType() {
        return getLastResponse().getContentType();
    }

    /**
     * @return the content of the last response as a {@link Document}
     */
    @Override
    public Document getLastResponseAsDom() {
        Document result = null;
        try {
            result = helper.getDom(getLastResponseInputStream());
        } catch (Exception e) {
            Throwables.propagate(e);
        }
        return result;
    }

    /**
     * @return the status code of the last response
     */
    @Override
    public int getLastResponseStatus() {
        MockHttpServletResponse response = getLastResponse();
//        int code = response.getStatusCode();
//        if (response.getStatusCode() == 200) {
//            code = response.getErrorCode();
//        }
//        return code;
        return response.getStatus();
    }

    /**
     * @return the content of the last response as an {@link InputStream}
     *
     * @throws Exception
     */
    @Override
    public InputStream getLastResponseInputStream() throws Exception {
        return new ByteArrayInputStream(getBinary(getLastResponse()));
    }

    /**
     * @return the allowed http methods of the last response
     */
    @Override
    public Set<String> getLastResponseAllowedMethods() {
        return Sets.newHashSet(getLastResponse().getHeader("ALLOW").split(","));
    }

    protected byte[] getBinary(MockHttpServletResponse response) {
//        try {
            return response.getContentAsByteArray();
//        } catch (Exception e) {
//            throw new RuntimeException("Whoops, did you change the MockRunner version? " +
//                     "If so, you might want to change this method too", e);
//        }
    }

    public void call(Method method, String resourceUri, JsonObject payload) {
        try {
            resourceUri = replaceVariables(resourceUri);
            this.lastResponse = helper.callInternal(method, "/geogig" + resourceUri, payload);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }

    /**
     * Invokes URI request with specified Content-Type. This is used for testing mismatches in request
     * body content and the Content-Type header.
     * @param method HTTP Method to invoke
     * @param resourceUri URI address to which to send the request
     * @param payload JSON object payload to encode into the request
     * @param contentType Specific Content-Type header value to send
     */
    public void callWithContentType(Method method, String resourceUri, JsonObject payload,
            String contentType) {
        try {
            resourceUri = replaceVariables(resourceUri);
            this.lastResponse = helper.callWithContentTypeInternal(method, "/geogig" + resourceUri,
                    payload, contentType);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }

    public void call(Method method, String resourceUri, Form form) {
        try {
            resourceUri = replaceVariables(resourceUri);
            this.lastResponse = helper.callInternal(method, "/geogig" + resourceUri, form);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }
}
