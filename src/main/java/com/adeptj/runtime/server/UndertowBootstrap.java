/*
###############################################################################
#                                                                             #
#    Copyright 2016, AdeptJ (http://adeptj.com)                               #
#                                                                             #
#    Licensed under the Apache License, Version 2.0 (the "License");          #
#    you may not use this file except in compliance with the License.         #
#    You may obtain a copy of the License at                                  #
#                                                                             #
#        http://www.apache.org/licenses/LICENSE-2.0                           #
#                                                                             #
#    Unless required by applicable law or agreed to in writing, software      #
#    distributed under the License is distributed on an "AS IS" BASIS,        #
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. #
#    See the License for the specific language governing permissions and      #
#    limitations under the License.                                           #
#                                                                             #
###############################################################################
*/
package com.adeptj.runtime.server;

import com.adeptj.runtime.common.Constants;
import com.adeptj.runtime.common.EnvironmentUtils;
import com.adeptj.runtime.common.ServerMode;
import com.adeptj.runtime.common.IOUtils;
import com.adeptj.runtime.common.Verb;
import com.adeptj.runtime.config.Configs;
import com.adeptj.runtime.core.ContainerInitializer;
import com.adeptj.runtime.logging.LogbackBootstrap;
import com.adeptj.runtime.osgi.FrameworkStartupHandler;
import com.adeptj.runtime.servlet.AdminAuthServlet;
import com.adeptj.runtime.servlet.AdminDashboardServlet;
import com.adeptj.runtime.servlet.AdminErrorServlet;
import com.typesafe.config.Config;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.Undertow.Builder;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.PredicateHandler;
import io.undertow.server.handlers.RequestLimitingHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ErrorPage;
import io.undertow.servlet.api.SecurityConstraint;
import io.undertow.servlet.api.ServletContainerInitializerInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.Options;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.channels.ServerSocketChannel;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.adeptj.runtime.common.Constants.ADMIN_LOGIN_URI;
import static com.adeptj.runtime.common.Constants.ADMIN_LOGOUT_URI;
import static com.adeptj.runtime.common.Constants.CMD_LAUNCH_BROWSER;
import static com.adeptj.runtime.common.Constants.CONTEXT_PATH;
import static com.adeptj.runtime.common.Constants.DEPLOYMENT_NAME;
import static com.adeptj.runtime.common.Constants.HEADER_POWERED_BY;
import static com.adeptj.runtime.common.Constants.HEADER_SERVER;
import static com.adeptj.runtime.common.Constants.KEY_ALLOWED_METHODS;
import static com.adeptj.runtime.common.Constants.KEY_HEADER_POWERED_BY;
import static com.adeptj.runtime.common.Constants.KEY_HEADER_SERVER;
import static com.adeptj.runtime.common.Constants.KEY_HOST;
import static com.adeptj.runtime.common.Constants.KEY_HTTP;
import static com.adeptj.runtime.common.Constants.KEY_MAX_CONCURRENT_REQS;
import static com.adeptj.runtime.common.Constants.KEY_PORT;
import static com.adeptj.runtime.common.Constants.OSGI_CONSOLE_URL;
import static com.adeptj.runtime.common.Constants.STARTUP_INFO;
import static com.adeptj.runtime.common.Constants.SYS_PROP_SERVER_PORT;

/**
 * UndertowBootstrap: Provision the Undertow Http Server.
 *
 * @author Rakesh.Kumar, AdeptJ
 */
public final class UndertowBootstrap {

    private static final String KEY_IGNORE_FLUSH = "common.ignore-flush";

    private static final int SYS_TASK_THREAD_MULTIPLIER = 2;

    private static final int WORKER_TASK_THREAD_MULTIPLIER = 8;

    private static final String KEY_WORKER_TASK_MAX_THREADS = "worker-task-max-threads";

    private static final String KEY_WORKER_TASK_CORE_THREADS = "worker-task-core-threads";

    private static final String KEY_WORKER_OPTIONS = "worker-options";

    private static final String SYS_PROP_SERVER_MODE = "adeptj.server.mode";

    private static final String MODE_DEV = "DEV";

    private static final String MODE_PROD = "PROD";

    private static final String KEY_KEYSTORE = "keyStore";

    private static final String KEY_KEYPWD = "keyPwd";

    private static final String KEY_KEYSTORE_PWD = "keyStorePwd";

    private static final String KEY_HTTPS = "https";

    private static final String SYS_PROP_ENABLE_HTTP2 = "enable.http2";

    private static final String KEY_AJP = "ajp";

    private static final String SYS_PROP_ENABLE_AJP = "enable.ajp";

    private static final String SYS_PROP_CHECK_PORT = "check.server.port";

    private static final String KEY_DEFAULT_ENCODING = "common.default-encoding";

    private static final String PROTOCOL_TLS = "TLS";

    // No instantiation.
    private UndertowBootstrap() {
    }

    public static void bootstrap(Map<String, String> arguments) throws Exception {
        Config undertowConf = Configs.INSTANCE.undertow();
        Config httpConf = undertowConf.getConfig(KEY_HTTP);
        Logger logger = LoggerFactory.getLogger(UndertowBootstrap.class);
        int httpPort = handlePortAvailability(httpConf, logger);
        logger.info("Starting AdeptJ Runtime @ port: [{}]", httpPort);
        logger.info(IOUtils.toString(UndertowBootstrap.class.getResourceAsStream(STARTUP_INFO)));
        Builder undertowBuilder = Undertow.builder();
        DeploymentManager manager = Servlets.newContainer().addDeployment(deploymentInfo(undertowConf));
        manager.deploy();
        boolean prodMode = handleProdMode(undertowBuilder, undertowConf, logger);
        HttpHandler handler = prodMode ? manager.start() : new SetHeadersHandler(manager.start(), serverHeaders(undertowConf));
        ServerOptions.build(undertowBuilder, undertowConf);
        undertowBuilder.addHttpListener(httpPort, httpConf.getString(KEY_HOST));
        enableHttp2(undertowConf, undertowBuilder, logger);
        enableAJP(undertowConf, undertowBuilder, logger);
        Undertow server = undertowBuilder.setHandler(rootHandler(handler, undertowConf)).build();
        server.start();
        Runtime.getRuntime().addShutdownHook(new ShutdownHook(server, manager));
        launchBrowser(arguments, httpPort);
    }

    private static void launchBrowser(Map<String, String> arguments, int httpPort) throws IOException {
        if (Boolean.parseBoolean(arguments.get(CMD_LAUNCH_BROWSER))) {
            EnvironmentUtils.launchBrowser(new URL(String.format(OSGI_CONSOLE_URL, httpPort)));
        }
    }

    private static boolean handleProdMode(Builder undertowBuilder, Config undertowConf, Logger logger) {
        boolean prodMode = ServerMode.PROD.toString().equalsIgnoreCase(System.getProperty(SYS_PROP_SERVER_MODE));
        if (prodMode) {
            Config workerOptions = undertowConf.getConfig(KEY_WORKER_OPTIONS);
            // defaults to 64
            int coreTaskThreadsConfig = workerOptions.getInt(KEY_WORKER_TASK_CORE_THREADS);
            // defaults to double of [worker-task-core-threads] i.e 128
            int maxTaskThreadsConfig = workerOptions.getInt(KEY_WORKER_TASK_MAX_THREADS);
            int sysTaskThreads = Runtime.getRuntime().availableProcessors() * WORKER_TASK_THREAD_MULTIPLIER;
            if (sysTaskThreads > coreTaskThreadsConfig) {
                undertowBuilder.setWorkerOption(Options.WORKER_TASK_CORE_THREADS, sysTaskThreads);
            } else {
                undertowBuilder.setWorkerOption(Options.WORKER_TASK_CORE_THREADS, coreTaskThreadsConfig);
            }
            int calcMaxTaskThreadsConfig = sysTaskThreads * SYS_TASK_THREAD_MULTIPLIER;
            if (calcMaxTaskThreadsConfig > maxTaskThreadsConfig) {
                undertowBuilder.setWorkerOption(Options.WORKER_TASK_MAX_THREADS, calcMaxTaskThreadsConfig);
            } else {
                undertowBuilder.setWorkerOption(Options.WORKER_TASK_MAX_THREADS, maxTaskThreadsConfig);
            }
        }
        logger.info("Bootstrapping AdeptJ Runtime in [{}] mode.", prodMode ? MODE_PROD : MODE_DEV);
        return prodMode;
    }

    private static void enableHttp2(Config undertowConf, Builder undertowBuilder, Logger logger) throws Exception {
        if (Boolean.getBoolean(SYS_PROP_ENABLE_HTTP2)) {
            Config httpsConf = undertowConf.getConfig(KEY_HTTPS);
            char[] keyStorePwd = httpsConf.getString(KEY_KEYSTORE_PWD).toCharArray();
            char[] keyPwd = httpsConf.getString(KEY_KEYPWD).toCharArray();
            int httpsPort = httpsConf.getInt(KEY_PORT);
            undertowBuilder.addHttpsListener(httpsPort, httpsConf.getString(KEY_HOST),
                    sslContext(keyStore(httpsConf.getString(KEY_KEYSTORE), keyStorePwd), keyPwd));
            logger.info("HTTP2 enabled @ port: [{}]", httpsPort);
        }
    }

    private static void enableAJP(Config undertowConf, Builder undertowBuilder, Logger logger) {
        if (Boolean.getBoolean(SYS_PROP_ENABLE_AJP)) {
            Config ajpConf = undertowConf.getConfig(KEY_AJP);
            int ajpPort = ajpConf.getInt(KEY_PORT);
            undertowBuilder.addAjpListener(ajpPort, ajpConf.getString(KEY_HOST));
            logger.info("AJP enabled @ port: [{}]", ajpPort);
        }
    }

    private static int handlePortAvailability(Config httpConf, Logger logger) {
        String propertyPort = System.getProperty(SYS_PROP_SERVER_PORT);
        int port;
        if (propertyPort == null || propertyPort.isEmpty()) {
            port = httpConf.getInt(KEY_PORT);
            logger.warn("No port specified via system property: [{}], using default port: [{}]", SYS_PROP_SERVER_PORT, port);
        } else {
            port = Integer.parseInt(propertyPort);
        }
        // Shall we do it ourselves or let server do it later? Problem may arise in OSGi Framework provisioning as it is being
        // started already and another server start(from same location) will again start new OSGi Framework which may interfere
        // with already started OSGi Framework as the bundle deployed, heap dump, OSGi configurations directory is common,
        // this is unknown at this moment but just to be on safer side doing this.
        if (Boolean.getBoolean(SYS_PROP_CHECK_PORT) && !isPortAvailable(port, logger)) {
            logger.error("JVM shutting down!!");
            // Let the LOGBACK cleans up it's state.
            LogbackBootstrap.stopLoggerContext();
            System.exit(-1);
        }
        return port;
    }

    private static boolean isPortAvailable(int port, Logger logger) {
        boolean portAvailable;
        try (ServerSocketChannel channel = ServerSocketChannel.open()) {
            channel.socket().setReuseAddress(true);
            channel.socket().bind(new InetSocketAddress(port));
            portAvailable = true;
        } catch (BindException ex) {
            logger.error("BindException while aquiring port: [{}], cause:", port, ex);
            portAvailable = false;
        } catch (IOException ex) {
            logger.error("IOException while aquiring port: [{}], cause:", port, ex);
            portAvailable = false;
        }
        return portAvailable;
    }

    private static Map<HttpString, String> serverHeaders(Config undertowConfig) {
        Map<HttpString, String> headers = new HashMap<>();
        headers.put(HttpString.tryFromString(HEADER_SERVER), undertowConfig.getString(KEY_HEADER_SERVER));
        headers.put(HttpString.tryFromString(HEADER_POWERED_BY), undertowConfig.getString(KEY_HEADER_POWERED_BY));
        return headers;
    }

    private static PredicateHandler predicateHandler(HttpHandler initialHandler) {
        return Handlers.predicate(new ContextRootPredicate(), Handlers.redirect(Constants.OSGI_WEBCONSOLE_URI), initialHandler);
    }

    private static Set<HttpString> allowedMethods(Config undertowConfig) {
        return undertowConfig.getStringList(KEY_ALLOWED_METHODS).stream().map(Verb::from).collect(Collectors.toSet());
    }

    private static HttpHandler rootHandler(HttpHandler initialHandler, Config undertowConfig) {
        return Handlers.gracefulShutdown(new RequestLimitingHandler(undertowConfig.getInt(KEY_MAX_CONCURRENT_REQS),
                new AllowedMethodsHandler(predicateHandler(initialHandler), allowedMethods(undertowConfig))));
    }

    private static List<ErrorPage> errorPages(Config undertowConfig) {
        return undertowConfig.getObject("error-pages").unwrapped().entrySet().stream()
                .map(entry -> Servlets.errorPage((String) entry.getValue(), Integer.parseInt(entry.getKey())))
                .collect(Collectors.toList());
    }

    private static ServletContainerInitializerInfo sciInfo() {
        return new ServletContainerInitializerInfo(ContainerInitializer.class,
                new ImmediateInstanceFactory<>(new ContainerInitializer()), Collections.singleton(FrameworkStartupHandler.class));
    }

    private static SecurityConstraint securityConstraint(Config undertowConfig) {
        return Servlets.securityConstraint().addRolesAllowed(undertowConfig.getStringList("common.auth-roles"))
                .addWebResourceCollection(Servlets.webResourceCollection()
                        .addHttpMethods(undertowConfig.getStringList("common.secured-urls-allowed-methods"))
                        .addUrlPatterns(undertowConfig.getStringList("common.secured-urls")));
    }

    private static List<ServletInfo> servlets() {
        List<ServletInfo> servlets = new ArrayList<>();
        servlets.add(Servlets.servlet(AdminErrorServlet.class).addMapping("/admin/error/*"));
        servlets.add(Servlets.servlet(AdminDashboardServlet.class).addMapping("/admin/dashboard/*"));
        servlets.add(Servlets.servlet(AdminAuthServlet.class).addMappings(ADMIN_LOGIN_URI, ADMIN_LOGOUT_URI));
        return servlets;
    }

    private static MultipartConfigElement defaultMultipartConfig(Config undertowConfig) {
        return new MultipartConfigElement(undertowConfig.getString("common.multipart-file-location"),
                undertowConfig.getLong("common.multipart-max-file-size"),
                undertowConfig.getLong("common.multipart-max-request-size"),
                undertowConfig.getInt("common.multipart-file-size-threshold"));
    }

    private static DeploymentInfo deploymentInfo(Config undertowConfig) {
        return Servlets.deployment().setDeploymentName(DEPLOYMENT_NAME).setContextPath(CONTEXT_PATH)
                .setClassLoader(UndertowBootstrap.class.getClassLoader())
                .setIgnoreFlush(undertowConfig.getBoolean(KEY_IGNORE_FLUSH))
                .setDefaultEncoding(undertowConfig.getString(KEY_DEFAULT_ENCODING))
                .setDefaultSessionTimeout(undertowConfig.getInt("common.session-timeout"))
                .setInvalidateSessionOnLogout(undertowConfig.getBoolean("common.invalidate-session-on-logout"))
                .setIdentityManager(new TextIdentityManager(undertowConfig))
                .setUseCachedAuthenticationMechanism(undertowConfig.getBoolean("common.use-cached-auth-mechanism"))
                .setLoginConfig(Servlets.loginConfig(HttpServletRequest.FORM_AUTH, "AdeptJ Realm", ADMIN_LOGIN_URI, ADMIN_LOGIN_URI))
                .addServletContainerInitalizer(sciInfo()).addSecurityConstraint(securityConstraint(undertowConfig))
                .addServlets(servlets()).addErrorPages(errorPages(undertowConfig))
                .setDefaultMultipartConfig(defaultMultipartConfig(undertowConfig))
                .addInitialHandlerChainWrapper(new ServletInitialHandlerWrapper(undertowConfig));
    }

    private static KeyStore keyStore(String keyStoreName, char[] keyStorePwd) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(UndertowBootstrap.class.getResourceAsStream(keyStoreName), keyStorePwd);
        return keyStore;
    }

    private static SSLContext sslContext(KeyStore keyStore, char[] keyPwd) throws NoSuchAlgorithmException, KeyManagementException, UnrecoverableKeyException, KeyStoreException {
        SSLContext sslContext = SSLContext.getInstance(PROTOCOL_TLS);
        sslContext.init(keyMgrs(keyStore, keyPwd), null, null);
        return sslContext;
    }

    private static KeyManager[] keyMgrs(KeyStore keyStore, char[] keyPwd) throws NoSuchAlgorithmException, UnrecoverableKeyException, KeyStoreException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPwd);
        return kmf.getKeyManagers();
    }
}