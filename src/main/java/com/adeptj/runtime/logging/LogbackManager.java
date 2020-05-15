/*
###############################################################################
#                                                                             #
#    Copyright 2016, AdeptJ (http://www.adeptj.com)                           #
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

package com.adeptj.runtime.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.FileSize;
import com.adeptj.runtime.common.OSGiUtil;
import com.adeptj.runtime.common.Times;
import com.adeptj.runtime.config.Configs;
import com.typesafe.config.Config;
import org.osgi.framework.ServiceReference;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.osgi.framework.Constants.SERVICE_PID;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;

/**
 * LogbackManager
 *
 * @author Rakesh.Kumar, AdeptJ
 */
public final class LogbackManager {

    static final String KEY_ROOT_LOG_LEVEL = "root-log-level";

    private static final String KEY_CONSOLE_APPENDER_NAME = "console-appender-name";

    private static final String KEY_LOG_PATTERN_CONSOLE = "log-pattern-console";

    private static final String KEY_LOGGERS = "loggers";

    private static final String KEY_LOGGER_NAME = "name";

    private static final String KEY_LOGGER_LEVEL = "level";

    private static final String KEY_LOGGER_ADDITIVITY = "additivity";

    private static final String KEY_SERVER_LOG_FILE = "server-log-file";

    private static final String KEY_ROLLOVER_SERVER_LOG_FILE = "rollover-server-log-file";

    private static final String KEY_FILE_APPENDER_NAME = "file-appender-name";

    private static final String KEY_ASYNC_APPENDER_NAME = "async-appender-name";

    private static final String KEY_LOG_PATTERN_FILE = "log-pattern-file";

    private static final String KEY_LOG_MAX_HISTORY = "log-max-history";

    private static final String KEY_LOG_MAX_SIZE = "log-max-size";

    private static final String KEY_ASYNC_LOG_QUEUE_SIZE = "async-log-queue-size";

    private static final String KEY_ASYNC_LOG_DISCARD_THRESHOLD = "async-log-discardingThreshold";

    private static final String KEY_IMMEDIATE_FLUSH = "file-appender-immediate-flush";

    private static final String KEY_OSGI_LOGGER_NAMES = "logger.names";

    private static final String KEY_OSGI_LOGGER_LEVEL = "logger.level";

    private static final String KEY_OSGI_LOGGER_ADDITIVITY = "logger.additivity";

    private static final String SYS_PROP_LOG_ASYNC = "log.async";

    private static final String SYS_PROP_LOG_IMMEDIATE_FLUSH = "log.immediate.flush";

    private static final String HIGHLIGHT = "highlight";

    private static final String THREAD = "thread";

    private static final String LC_NAME = "AdeptJ";

    private ConsoleAppender<ILoggingEvent> consoleAppender;

    private RollingFileAppender<ILoggingEvent> fileAppender;

    private final LoggerContext loggerContext;

    private final ContextUtil contextUtil;

    private final Map<String, LoggerConfig> configByPid;

    private final Map<String, LoggerConfig> configByCategory;

    LogbackManager(LoggerContext loggerContext) {
        this.loggerContext = loggerContext;
        this.loggerContext.setName(LC_NAME);
        this.contextUtil = new ContextUtil(loggerContext);
        PatternLayout.defaultConverterMap.put(HIGHLIGHT, DebugLevelHighlightingConverter.class.getName());
        PatternLayout.defaultConverterMap.put(THREAD, TrimThreadNameConverter.class.getName());
        this.configByPid = new ConcurrentHashMap<>();
        this.configByCategory = new ConcurrentHashMap<>();
    }

    public void stopLoggerContext() {
        this.loggerContext.stop();
    }

    public void addOSGiLoggers(ServiceReference<?> reference) {
        Logger logger = this.loggerContext.getLogger(this.getClass());
        Set<String> categories = OSGiUtil.arrayToSet(reference, KEY_OSGI_LOGGER_NAMES);
        int size = categories.size();
        // <<---------------------------- Validations Start ---------------------------->>
        if (size == 0) {
            logger.warn("Can't add loggers because logger.names array property is empty!!");
            return;
        }
        if (size == 1 && categories.contains(ROOT_LOGGER_NAME)) {
            logger.warn("Adding a ROOT logger is prohibited!!");
            return;
        }
        if (categories.remove(ROOT_LOGGER_NAME)) {
            logger.warn("Removed ROOT logger from the categories as adding a ROOT logger is prohibited!!");
        }
        for (String category : categories) {
            LoggerConfig config = this.configByCategory.get(category);
            if (config != null) {
                logger.error("Category [{}] already defined by configuration {}", category, config.getConfigPid());
                return;
            }
        }
        // <<---------------------------- Validations End ---------------------------->>
        String pid = OSGiUtil.getString(reference, SERVICE_PID);
        String level = OSGiUtil.getString(reference, KEY_OSGI_LOGGER_LEVEL);
        boolean additivity = OSGiUtil.getBoolean(reference, KEY_OSGI_LOGGER_ADDITIVITY);
        LoggerConfig config = new LoggerConfig(pid, categories, level, additivity);
        categories.forEach(category -> this.configByCategory.put(category, config));
        this.configByPid.put(pid, config);
        logger.info("Adding loggers for categories {} with level {}", categories, level);
        categories.forEach(category -> this.addLogger(category, level, additivity));
    }

    public void resetLoggers(ServiceReference<?> reference) {
        LoggerConfig config = this.configByPid.remove(OSGiUtil.getString(reference, SERVICE_PID));
        if (config == null) {
            return;
        }
        long startTime = System.nanoTime();
        this.configByCategory.keySet().removeAll(config.getCategories());
        Logger logger = this.loggerContext.getLogger(this.getClass());
        logger.info("Removing loggers for categories {} with level {}", config.getCategories(), config.getLevel());
        this.contextUtil.addInfo(String.format("Resetting LoggerContext %s", this.loggerContext.getName()));
        this.loggerContext.reset();
        this.consoleAppender = null;
        this.fileAppender = null;
        Config loggingCfg = Configs.of().logging();
        this.initConsoleAppender(loggingCfg);
        this.initRollingFileAppender(loggingCfg);
        this.contextUtil.addInfo("ConsoleAppender and RollingFileAppender reinitialized!");
        this.changeLevelAndAddAppendersToRootLogger(loggingCfg);
        this.contextUtil.addInfo("ROOT Logger reinitialized!");
        // Add the server config loggers again.
        this.addServerConfigLoggers(loggingCfg);
        // Reconfigure other OSGi loggers.
        this.configByPid.values().forEach(cfg -> {
            Set<String> categories = cfg.getCategories();
            String level = cfg.getLevel();
            logger.info("Adding loggers for categories {} with level {}", categories, level);
            categories.forEach(category -> this.addLogger(category, level, cfg.isAdditivity()));
        });
        this.contextUtil.addInfo("Server config loggers reconfigured!");
        this.contextUtil.addInfo(String.format("Reset of LoggerContext %s done!", this.loggerContext.getName()));
        logger.info("LoggerContext reset took [{}] ms!", Times.elapsedMillis(startTime));
    }

    void addServerConfigLoggers(Config loggingCfg) {
        loggingCfg.getConfigList(KEY_LOGGERS)
                .forEach(loggerCfg -> this.addLogger(loggerCfg.getString(KEY_LOGGER_NAME).trim(),
                        loggerCfg.getString(KEY_LOGGER_LEVEL).trim(),
                        loggerCfg.getBoolean(KEY_LOGGER_ADDITIVITY)));
    }

    private void addLogger(String name, String level, boolean additivity) {
        Logger logger = this.loggerContext.getLogger(name);
        logger.setLevel(Level.toLevel(level));
        logger.setAdditive(additivity);
        logger.addAppender(this.consoleAppender);
        logger.addAppender(this.fileAppender);
    }

    void changeLevelAndAddAppendersToRootLogger(Config loggingCfg) {
        Logger root = this.loggerContext.getLogger(ROOT_LOGGER_NAME);
        root.setLevel(Level.toLevel(loggingCfg.getString(KEY_ROOT_LOG_LEVEL)));
        root.addAppender(this.consoleAppender);
        root.addAppender(this.fileAppender);
    }

    void initConsoleAppender(Config loggingCfg) {
        ConsoleAppender<ILoggingEvent> consoleAppender = new ConsoleAppender<>();
        consoleAppender.setContext(this.loggerContext);
        consoleAppender.setName(loggingCfg.getString(KEY_CONSOLE_APPENDER_NAME));
        consoleAppender.setEncoder(this.newLayoutEncoder(loggingCfg.getString(KEY_LOG_PATTERN_CONSOLE)));
        consoleAppender.setWithJansi(true);
        consoleAppender.start();
        this.consoleAppender = consoleAppender;
    }

    void initRollingFileAppender(Config loggingCfg) {
        FileAppenderConfig appenderConfig = this.createFileAppenderConfig(loggingCfg);
        RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<>();
        fileAppender.setContext(this.loggerContext);
        fileAppender.setName(appenderConfig.getAppenderName());
        fileAppender.setFile(appenderConfig.getLogFile());
        fileAppender.setAppend(true);
        fileAppender.setEncoder(this.newLayoutEncoder(appenderConfig.getPattern()));
        fileAppender.setImmediateFlush(Boolean.getBoolean(SYS_PROP_LOG_IMMEDIATE_FLUSH));
        if (!fileAppender.isImmediateFlush()) {
            fileAppender.setImmediateFlush(appenderConfig.isImmediateFlush());
        }
        SizeAndTimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new SizeAndTimeBasedRollingPolicy<>();
        rollingPolicy.setContext(this.loggerContext);
        rollingPolicy.setParent(fileAppender);
        rollingPolicy.setMaxFileSize(FileSize.valueOf(appenderConfig.getLogMaxSize()));
        rollingPolicy.setFileNamePattern(appenderConfig.getRolloverFile());
        rollingPolicy.setMaxHistory(appenderConfig.getLogMaxHistory());
        rollingPolicy.start();
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.setTriggeringPolicy(rollingPolicy);
        fileAppender.start();
        // Add AsyncAppender support.
        if (appenderConfig.isLogAsync()) {
            this.initAsyncAppender(appenderConfig, fileAppender);
        }
        this.fileAppender = fileAppender;
    }

    private PatternLayoutEncoder newLayoutEncoder(String logPattern) {
        PatternLayoutEncoder layoutEncoder = new PatternLayoutEncoder();
        layoutEncoder.setContext(this.loggerContext);
        layoutEncoder.setPattern(logPattern);
        layoutEncoder.setCharset(UTF_8);
        layoutEncoder.start();
        return layoutEncoder;
    }

    private void initAsyncAppender(FileAppenderConfig rollingFileConfig, FileAppender<ILoggingEvent> fileAppender) {
        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setContext(this.loggerContext);
        asyncAppender.setName(rollingFileConfig.getAsyncAppenderName());
        asyncAppender.setQueueSize(rollingFileConfig.getAsyncLogQueueSize());
        asyncAppender.setDiscardingThreshold(rollingFileConfig.getAsyncLogDiscardingThreshold());
        asyncAppender.addAppender(fileAppender);
        asyncAppender.start();
    }

    private FileAppenderConfig createFileAppenderConfig(Config loggingCfg) {
        return FileAppenderConfig.builder()
                .appenderName(loggingCfg.getString(KEY_FILE_APPENDER_NAME))
                .logFile(loggingCfg.getString(KEY_SERVER_LOG_FILE))
                .pattern(loggingCfg.getString(KEY_LOG_PATTERN_FILE))
                .immediateFlush(loggingCfg.getBoolean(KEY_IMMEDIATE_FLUSH))
                .logMaxSize(loggingCfg.getString(KEY_LOG_MAX_SIZE))
                .rolloverFile(loggingCfg.getString(KEY_ROLLOVER_SERVER_LOG_FILE))
                .logMaxHistory(loggingCfg.getInt(KEY_LOG_MAX_HISTORY))
                .logAsync(Boolean.getBoolean(SYS_PROP_LOG_ASYNC))
                .asyncAppenderName(loggingCfg.getString(KEY_ASYNC_APPENDER_NAME))
                .asyncLogQueueSize(loggingCfg.getInt(KEY_ASYNC_LOG_QUEUE_SIZE))
                .asyncLogDiscardingThreshold(loggingCfg.getInt(KEY_ASYNC_LOG_DISCARD_THRESHOLD))
                .build();
    }

    void initLevelChangePropagator() {
        LevelChangePropagator propagator = new LevelChangePropagator();
        propagator.setContext(this.loggerContext);
        propagator.setResetJUL(true);
        propagator.start();
        this.loggerContext.addListener(propagator);
    }
}
