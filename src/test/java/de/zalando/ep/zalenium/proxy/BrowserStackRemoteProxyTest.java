package de.zalando.ep.zalenium.proxy;


import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.awaitility.Duration;
import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.ExternalSessionKey;
import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.server.jmx.JMXHelper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.zalando.ep.zalenium.dashboard.Dashboard;
import de.zalando.ep.zalenium.dashboard.TestInformation;
import de.zalando.ep.zalenium.util.CommonProxyUtilities;
import de.zalando.ep.zalenium.util.Environment;
import de.zalando.ep.zalenium.util.SimpleRegistry;
import de.zalando.ep.zalenium.util.TestUtils;

public class BrowserStackRemoteProxyTest {

    private BrowserStackRemoteProxy browserStackProxy;
    private GridRegistry registry;
    private Boolean firstMockTest = false;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @SuppressWarnings("ConstantConditions")
    @Before
    public void setUp() {
        try {
            ObjectName objectName = new ObjectName("org.seleniumhq.grid:type=Hub");
            ManagementFactory.getPlatformMBeanServer().getObjectInstance(objectName);
            new JMXHelper().unregister(objectName);
        } catch (MalformedObjectNameException | InstanceNotFoundException e) {
            // Might be that the object does not exist, it is ok. Nothing to do, this is just a cleanup task.
        }
        registry = new SimpleRegistry();
        // Creating the configuration and the registration request of the proxy (node)
        RegistrationRequest request = TestUtils.getRegistrationRequestForTesting(30002,
                BrowserStackRemoteProxy.class.getCanonicalName());
        CommonProxyUtilities commonProxyUtilities = mock(CommonProxyUtilities.class);
        when(commonProxyUtilities.readJSONFromUrl(anyString(), anyString(), anyString())).thenReturn(null);
        BrowserStackRemoteProxy.setCommonProxyUtilities(commonProxyUtilities);
        browserStackProxy = BrowserStackRemoteProxy.getNewInstance(request, registry);

        // We add both nodes to the registry
        registry.add(browserStackProxy);

        // Creating the configuration and the registration request of the proxy (node)
        RegistrationRequest proxyRequest = TestUtils.getRegistrationRequestForTesting(40000,
                DockerSeleniumRemoteProxy.class.getCanonicalName());
        proxyRequest.getConfiguration().capabilities.clear();
        proxyRequest.getConfiguration().capabilities.addAll(TestUtils.getDockerSeleniumCapabilitiesForTesting());

        // Creating the proxy
        DockerSeleniumRemoteProxy proxy = DockerSeleniumRemoteProxy.getNewInstance(proxyRequest, registry);
        registry.add(proxy);
    }

    @After
    public void tearDown() throws MalformedObjectNameException {
        ObjectName objectName = new ObjectName("org.seleniumhq.grid:type=RemoteProxy,node=\"http://hub-cloud.browserstack.com:80\"");
        new JMXHelper().unregister(objectName);
        objectName = new ObjectName("org.seleniumhq.grid:type=RemoteProxy,node=\"http://localhost:30000\"");
        new JMXHelper().unregister(objectName);
        BrowserStackRemoteProxy.restoreCommonProxyUtilities();
        BrowserStackRemoteProxy.restoreGa();
        BrowserStackRemoteProxy.restoreEnvironment();
    }

    @Test
    public void checkProxyOrdering() {
        // Checking that the DockerSeleniumStarterProxy should come before BrowserStackRemoteProxy
        List<RemoteProxy> sorted = registry.getAllProxies().getSorted();
        Assert.assertEquals(2, sorted.size());
        Assert.assertEquals(DockerSeleniumRemoteProxy.class, sorted.get(0).getClass());
        Assert.assertEquals(BrowserStackRemoteProxy.class, sorted.get(1).getClass());
    }

    @Test
    public void sessionIsCreatedWithCapabilitiesThatDockerSeleniumCannotProcess() {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.SAFARI);
        requestedCapability.put(CapabilityType.PLATFORM_NAME, Platform.MAC);

        Assert.assertEquals(0, browserStackProxy.getNumberOfSessions());
        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);

        Assert.assertNotNull(testSession);
        Assert.assertEquals(1, browserStackProxy.getNumberOfSessions());
    }


    @Test
    public void credentialsAreAddedInSessionCreation() throws IOException {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.IE);
        requestedCapability.put(CapabilityType.PLATFORM_NAME, Platform.WIN8);

        // Getting a test session in the sauce labs node
        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(testSession);

        // We need to mock all the needed objects to forward the session and see how in the beforeMethod
        // the BrowserStack user and api key get added to the body request.
        WebDriverRequest request = TestUtils.getMockedWebDriverRequestStartSession(BrowserType.IE, Platform.WIN8);

        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream stream = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(stream);

        testSession.setExternalKey(new ExternalSessionKey("BrowserStack Test"));
        testSession.forward(request, response, true);

        Environment env = new Environment();

        // The body should now have the BrowserStack variables
        String expectedBody = String.format("{\"desiredCapabilities\":{\"browserName\":\"internet explorer\",\"platformName\":" +
                        "\"WIN8\",\"browserstack.user\":\"%s\",\"browserstack.key\":\"%s\"}}",
                env.getStringEnvVariable("BROWSER_STACK_USER", ""),
                env.getStringEnvVariable("BROWSER_STACK_KEY", ""));
        verify(request).setBody(expectedBody);
    }

    @Test
    public void testInformationIsRetrievedWhenStoppingSession() throws IOException {
        // Capability which should result in a created session
        try {
            // browser session
            sessionMockTest(BrowserType.CHROME, Platform.WIN10,
                    "browserstack_browser_testinformation.json",
                    "77e51cead8e6e37b0a0feb0dfa69325b2c4acf97",
                    "browserstack_loadZalandoPageAndCheckTitle_safari_OS_X",
                    "safari 12.1, OS X Mojave",
                    "https://automate.browserstack.com/sessions/d27616b6b88fc593b54a5dd7fb72c8ebca" +
                            "7f510e/video?token=NFRqNEc0TVdvV1BhMVZhdmdrOGNjRFcrTEhxbUdUb1ZieEhxQVg5ZlNsWU0yQ2lnU2tGTTV4TEVyLzN" +
                            "uYWR4NEQ2ZllJcytQcGtHRWlPd0IrSnlOU2c9PS0td0VURUF0YmxlNEpicXB5YzZHdnpMQT09--2e1f2f625e7996f64e08d36" +
                            "3b85c332b88c35dbf&source=rest_api&diff=45798.42101425");

            // mobile browser session
            sessionMockTest(BrowserType.CHROME, Platform.IOS,
                    "browserstack_appium_browser_testinformation.json",
                    "3ae6c5ae782376c2626f5f567b64788270fccebb",
                    "browserstack_loadZalandoPageAndCheckTitle_iPhone_11_Pro_ios",
                    "iPhone 11 Pro N/A, ios 13.2",
                    "https://automate.browserstack.com/sessions/3ae6c5ae782376c2626f5f567b64788270fccebb/video?" +
                            "token=c3FkT2F4WHBzNjRQODhhME5YWW1BZXV2TUNNaFlWZGtMbURleGxRRFgrTW9OWmxuOVgxZjBPbWhMTHJ1TFcy" +
                            "Z0dEREFndWpkSythZldkUktyVFRTWUE9PS0taFlCVWI5SndMU0xZSHp5RjY0ZlFSUT09--e39f8c5ad872d2d2b7a5" +
                            "cff3bf25f12358491077&source=rest_api&diff=45400.927527361");

            // mobile app session
            sessionMockTest(BrowserType.CHROME, Platform.ANDROID,
                    "browserstack_appium_app_testinformation.json",
                    "40693fb3fca7b00aac49e72908a648fcfa7af47a",
                    "browserstack_loadZalandoPageAndCheckTitle_Google_Pixel_3_android",
                    "Google Pixel 3 app, android 9.0",
                    "https://app-automate.browserstack.com/sessions/40693fb3fca7b00aac49e72908a648fcfa7af47a/vi" +
                            "deo?token=cmtPS05EejFrQmZxd3R4WlowOWVLYUxsWVFRbUhJbHh1Q0czYzcvM1pIY1RQMENXR2VWd2xNMnI3aEtz" +
                            "NWUzeng3U0srU1VVRlFya2s5Q0cvbld3NWc9PS0teks3azkxSTEzRUlZT1N2QXNCVzFRQT09--0b5a5fadabfcf5ab" +
                            "acc201104f9ba76eea5aae3b&source=rest_api&diff=45840.954378879");

        } finally {
            BrowserStackRemoteProxy.restoreCommonProxyUtilities();
            BrowserStackRemoteProxy.restoreGa();
            BrowserStackRemoteProxy.restoreEnvironment();
            Dashboard.restoreCommonProxyUtilities();
        }
    }

    private void sessionMockTest(String browser, Platform platform, String informationSampleName, String mockSeleniumSessionId,
                                 String testFileName, String browserAndPlatform, String videoUrl) throws IOException {
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, browser);
        requestedCapability.put(CapabilityType.PLATFORM_NAME, platform);

        JsonElement informationSample = TestUtils.getTestInformationSample(informationSampleName);
        if (!firstMockTest) {
            TestUtils.ensureRequiredInputFilesExist(temporaryFolder);
            firstMockTest = true;
        }
        CommonProxyUtilities commonProxyUtilities = TestUtils.mockCommonProxyUtilitiesForDashboardTesting(temporaryFolder);
        Environment env = new Environment();
        String mockTestInformationUrl = "https://api-cloud.browserstack.com/app-automate/sessions/" + mockSeleniumSessionId + ".json";
        when(commonProxyUtilities.readJSONFromUrl(mockTestInformationUrl,
                env.getStringEnvVariable("BROWSER_STACK_USER", ""),
                env.getStringEnvVariable("BROWSER_STACK_KEY", ""))).thenReturn(informationSample);
        BrowserStackRemoteProxy.setCommonProxyUtilities(commonProxyUtilities);
        Dashboard.setCommonProxyUtilities(commonProxyUtilities);

        // Getting a test session in the sauce labs node
        BrowserStackRemoteProxy bsSpyProxy = spy(browserStackProxy);
        TestSession testSession = bsSpyProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(testSession);
        testSession.setExternalKey(new ExternalSessionKey(mockSeleniumSessionId));

        // We release the session, the node should be free
        WebDriverRequest request = mock(WebDriverRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestType()).thenReturn(RequestType.STOP_SESSION);
        testSession.getSlot().doFinishRelease();
        bsSpyProxy.afterCommand(testSession, request, response);

        verify(bsSpyProxy, timeout(1000 * 5)).getTestInformation(mockSeleniumSessionId);
        Callable<Boolean> callable = () -> BrowserStackRemoteProxy.addToDashboardCalled;
        await().pollInterval(Duration.FIVE_HUNDRED_MILLISECONDS).atMost(Duration.TWO_SECONDS).until(callable);
        TestInformation testInformation = bsSpyProxy.getTestInformation(mockSeleniumSessionId);
        Assert.assertEquals("loadZalandoPageAndCheckTitle", testInformation.getTestName());
        Assert.assertThat(testInformation.getFileName(),
                CoreMatchers.containsString(testFileName));
        Assert.assertEquals(browserAndPlatform, testInformation.getBrowserAndPlatform());
        Assert.assertEquals(videoUrl, testInformation.getVideoUrl());
    }

    @Test
    public void requestIsNotModifiedInOtherRequestTypes() throws IOException {
        // Capability which should result in a created session
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.IE);
        requestedCapability.put(CapabilityType.PLATFORM_NAME, Platform.WIN8);

        // Getting a test session in the sauce labs node
        TestSession testSession = browserStackProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(testSession);

        // We need to mock all the needed objects to forward the session and see how in the beforeMethod
        // the SauceLabs user and api key get added to the body request.
        WebDriverRequest request = mock(WebDriverRequest.class);
        when(request.getRequestURI()).thenReturn("session");
        when(request.getServletPath()).thenReturn("session");
        when(request.getContextPath()).thenReturn("");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestType()).thenReturn(RequestType.REGULAR);
        JsonObject jsonObject = new JsonObject();
        JsonObject desiredCapabilities = new JsonObject();
        desiredCapabilities.addProperty(CapabilityType.BROWSER_NAME, BrowserType.IE);
        desiredCapabilities.addProperty(CapabilityType.PLATFORM_NAME, Platform.WIN8.name());
        jsonObject.add("desiredCapabilities", desiredCapabilities);
        when(request.getBody()).thenReturn(jsonObject.toString());

        Enumeration<String> strings = Collections.emptyEnumeration();
        when(request.getHeaderNames()).thenReturn(strings);

        HttpServletResponse response = mock(HttpServletResponse.class);
        ServletOutputStream stream = mock(ServletOutputStream.class);
        when(response.getOutputStream()).thenReturn(stream);

        testSession.setExternalKey(new ExternalSessionKey("BrowserStack Test"));
        testSession.forward(request, response, true);

        // The body should not be affected and not contain the BrowserStack variables
        Assert.assertThat(request.getBody(), CoreMatchers.containsString(jsonObject.toString()));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.user")));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.key")));

        when(request.getMethod()).thenReturn("GET");

        testSession.forward(request, response, true);
        Assert.assertThat(request.getBody(), CoreMatchers.containsString(jsonObject.toString()));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.user")));
        Assert.assertThat(request.getBody(), CoreMatchers.not(CoreMatchers.containsString("browserstack.key")));
    }

    @Test
    public void checkVideoFileExtensionAndProxyName() {
        Assert.assertEquals(".mp4", browserStackProxy.getVideoFileExtension());
        Assert.assertEquals("BrowserStack", browserStackProxy.getProxyName());
    }
}
