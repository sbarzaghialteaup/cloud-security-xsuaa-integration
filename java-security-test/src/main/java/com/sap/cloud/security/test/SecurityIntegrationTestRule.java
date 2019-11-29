package com.sap.cloud.security.test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import javax.annotation.Nullable;
import javax.servlet.ServletException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.commons.io.IOUtils;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.sap.cloud.security.config.Service;
import com.sap.cloud.security.token.Token;
import com.sap.cloud.security.token.TokenClaims;
import com.sap.cloud.security.token.TokenHeader;
import com.sap.cloud.security.xsuaa.client.XsuaaDefaultEndpoints;

public class SecurityIntegrationTestRule extends ExternalResource {

	private static final Logger logger = LoggerFactory.getLogger(SecurityIntegrationTestRule.class);
	private static final String LOCALHOST_PATTERN = "http://localhost:%d";

	private RSAKeys keys;

	private int wireMockPort = 0;
	private WireMockRule wireMockRule;

	private boolean useApplicationServer;
	private Tomcat tomcat;
	private String webappDir;
	private int tomcatPort = 44195;
	private TemporaryFolder baseDir;
	private Service service;
	private String clientId;
	private String jwksUrl;

	private SecurityIntegrationTestRule() {
		// see factory method getInstance()
	}

	public static SecurityIntegrationTestRule getInstance(Service service) {
		SecurityIntegrationTestRule instance = new SecurityIntegrationTestRule();
		instance.keys = RSAKeys.generate();
		instance.service = service;
		return instance;
	}

	/**
	 * Specifies an embedded Tomcat as application server. It needs to be configured
	 * before the {@link #before()} method.
	 *
	 * @param pathToWebAppDir
	 *            e.g. "src/test/webapp"
	 * @return the rule itself.
	 */
	public SecurityIntegrationTestRule useApplicationServer(String pathToWebAppDir) {
		return useApplicationServer(pathToWebAppDir, tomcatPort);
	}

	/**
	 * Specifies an embedded Tomcat as application server. It needs to be configured
	 * before the {@link #before()} method.
	 *
	 * @param pathToWebAppDir
	 *            e.g. "src/test/webapp"
	 * @param port
	 *            the port on which the application service is started.
	 * @return the rule itself.
	 */
	public SecurityIntegrationTestRule useApplicationServer(String pathToWebAppDir, int port) {
		webappDir = pathToWebAppDir;
		useApplicationServer = true;
		tomcatPort = port;
		return this;
	}

	/**
	 * Overwrites the port on which the wire mock server runs. It needs to be
	 * configured before the {@link #before()} method. If the port is not specified
	 * or is set to 0, a free random port is chosen.
	 * 
	 * @param wireMockPort
	 *            the port on which the wire mock service is started.
	 * @return the rule itself.
	 */
	public SecurityIntegrationTestRule setPort(int wireMockPort) {
		this.wireMockPort = wireMockPort;
		return this;
	}

	/**
	 * Overwrites the client id (cid) claim of the token that is being generated. It
	 * needs to be configured before the {@link #before()} method.
	 *
	 * @param clientId
	 *            the port on which the wire mock service is started.
	 * @return the rule itself.
	 */
	public SecurityIntegrationTestRule setClientId(String clientId) {
		this.clientId = clientId;
		return this;
	}

	/**
	 * Overwrites the private/public key pair to be used. The private key is used to
	 * sign the jwt token. The public key is provided by jwks endpoint (on behalf of
	 * WireMock).
	 * <p>
	 * It needs to be configured before the {@link #before()} method.
	 *
	 * @param keys
	 *            the private/public key pair.
	 * @return the rule itself.
	 */
	public SecurityIntegrationTestRule setKeys(RSAKeys keys) {
		this.keys = keys;
		return this;
	}

	@Override
	protected void before() throws IOException {
		// start application server (for integration tests)
		if (useApplicationServer) {
			startTomcat();
		}
		setupWireMock();

		switch (service) {
		case XSUAA:
			configureForXsuaa();
			break;
		default:
			throw new UnsupportedOperationException("Service " + service + " is not yet supported.");
		}

		// starts WireMock (to stub communication to identity service)
	}

	/**
	 * Note: the JwtGenerator is fully configured as part of {@link #before()}
	 * method.
	 * 
	 * @return the preconfigured Jwt token generator
	 */
	public JwtGenerator getPreconfiguredJwtGenerator() {
		return JwtGenerator.getInstance(service)
				.withClaimValue(TokenClaims.XSUAA.CLIENT_ID, clientId)
				.withPrivateKey(keys.getPrivate())
				.withHeaderParameter(TokenHeader.JWKS_URL, jwksUrl);
	}

	/**
	 * Creates a very basic token on base of the preconfigured Jwt token generator.
	 * In case you like to specify further token claims, you can make use of
	 * {@link #getPreconfiguredJwtGenerator()}
	 *
	 * @return the token.
	 */
	public Token createToken() {
		return getPreconfiguredJwtGenerator().createToken();
	}

	/**
	 * Allows to stub further endpoints of the identity service. Returns null if the
	 * rule is not yet initialized as part of {@link #before()} method. You can find
	 * a detailed explanation on how to configure wire mock here:
	 * http://wiremock.org/docs/getting-started/
	 */
	@Nullable
	public WireMockRule getWireMockRule() {
		return wireMockRule;
	}

	/**
	 * Returns the URI of the embedded tomcat application server or null if not
	 * specified.
	 */
	@Nullable
	public String getAppServerUri() {
		if (!useApplicationServer) {
			return null;
		}
		return String.format(LOCALHOST_PATTERN, tomcatPort);
	}

	private void configureForXsuaa() throws IOException {
		// prepare endpoints provider
		XsuaaDefaultEndpoints endpointsProvider = new XsuaaDefaultEndpoints(
				String.format(LOCALHOST_PATTERN, wireMockPort));
		wireMockRule.stubFor(get(urlEqualTo(endpointsProvider.getJwksUri().getPath()))
				.willReturn(aResponse().withBody(createDefaultTokenKeyResponse())));
		jwksUrl = endpointsProvider.getJwksUri().toString();
	}

	private void setupWireMock() {
		if (wireMockPort == 0) {
			wireMockRule = new WireMockRule(options().dynamicPort());
		} else {
			wireMockRule = new WireMockRule(options().port(wireMockPort));
		}
		wireMockRule.start();
		wireMockPort = wireMockRule.port();
	}

	@Override
	protected void after() {
		wireMockRule.shutdown();
		if (useApplicationServer) {
			try {
				tomcat.stop();
				tomcat.destroy();
				baseDir.delete();
			} catch (LifecycleException e) {
				logger.error("Failed to properly stop the tomcat server!");
				throw new UnsupportedOperationException(e);
			}
		}
	}

	private String createDefaultTokenKeyResponse() throws IOException {
		return IOUtils.resourceToString("/token_keys_template.json", StandardCharsets.UTF_8)
				.replace("$kid", "default-kid")
				.replace("$public_key", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
	}

	private void startTomcat() throws IOException {
		baseDir = new TemporaryFolder();
		baseDir.create();
		tomcat = new Tomcat();
		tomcat.setBaseDir(baseDir.getRoot().getAbsolutePath());
		tomcat.setPort(tomcatPort);
		try {
			Context context = tomcat.addWebapp("", new File(webappDir).getAbsolutePath());
			File additionWebInfClasses = new File("target/classes");
			WebResourceRoot resources = new StandardRoot(context);
			resources.addPreResources(
					new DirResourceSet(resources, "/WEB-INF/classes", additionWebInfClasses.getAbsolutePath(), "/"));
			context.setResources(resources);
			tomcat.start();
		} catch (LifecycleException | ServletException e) {
			logger.error("Failed to start the tomcat server on port {}!", tomcatPort);
			throw new UnsupportedOperationException(e);
		}
	}
}
