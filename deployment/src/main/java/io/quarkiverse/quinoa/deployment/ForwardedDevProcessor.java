package io.quarkiverse.quinoa.deployment;

import static io.quarkiverse.quinoa.QuinoaRecorder.QUINOA_ROUTE_ORDER;
import static io.quarkiverse.quinoa.QuinoaRecorder.QUINOA_SPA_ROUTE_ORDER;
import static io.quarkus.deployment.annotations.ExecutionTime.RUNTIME_INIT;
import static io.quarkus.deployment.dev.testing.MessageFormat.RESET;
import static java.lang.String.join;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

import io.quarkiverse.quinoa.QuinoaHandlerConfig;
import io.quarkiverse.quinoa.QuinoaRecorder;
import io.quarkiverse.quinoa.deployment.packagemanager.PackageManager;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.console.ConsoleInstalledBuildItem;
import io.quarkus.deployment.console.StartupLogCompressor;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.resteasy.reactive.server.spi.ResumeOn404BuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.core.deployment.CoreVertxBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.deployment.WebsocketSubProtocolsBuildItem;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;

public class ForwardedDevProcessor {

    private static final Logger LOG = Logger.getLogger(ForwardedDevProcessor.class);
    private static final Predicate<Thread> PROCESS_THREAD_PREDICATE = thread -> thread.getName()
            .matches("Process (stdout|stderr) streamer");
    private static volatile DevServicesResultBuildItem.RunningDevService devService;

    @BuildStep(onlyIf = IsDevelopment.class)
    public ForwardedDevServerBuildItem prepareDevService(
            QuinoaConfig quinoaConfig,
            LaunchModeBuildItem launchMode,
            Optional<QuinoaDirectoryBuildItem> quinoaDir,
            BuildProducer<DevServicesResultBuildItem> devServices,
            Optional<ConsoleInstalledBuildItem> consoleInstalled,
            LoggingSetupBuildItem loggingSetup,
            CuratedApplicationShutdownBuildItem shutdown,
            LiveReloadBuildItem liveReload) {
        if (quinoaDir.isEmpty()) {
            return null;
        }
        final QuinoaDirectoryBuildItem quinoaDirectoryBuildItem = quinoaDir.get();
        QuinoaConfig oldConfig = liveReload.getContextObject(QuinoaConfig.class);
        liveReload.setContextObject(QuinoaConfig.class, quinoaConfig);
        final String devServerHost = quinoaConfig.devServer.host;
        final String devServerCommand = quinoaDirectoryBuildItem.getDevServerCommand();
        if (devService != null) {
            boolean shouldShutdownTheBroker = !quinoaConfig.equals(oldConfig);
            if (!shouldShutdownTheBroker) {
                if (quinoaDirectoryBuildItem.getDevServerPort().isEmpty()) {
                    throw new IllegalStateException(
                            "Quinoa package manager live coding shouldn't running with an empty the dev-server.port");
                }
                LOG.debug("Quinoa config did not change; no need to restart.");
                devServices.produce(devService.toBuildItem());
                return new ForwardedDevServerBuildItem(devServerHost, quinoaDirectoryBuildItem.getDevServerPort().getAsInt());
            }
            shutdownDevService();
        }

        if (oldConfig == null) {
            Runnable closeTask = () -> {
                if (devService != null) {
                    shutdownDevService();
                }
                devService = null;
            };
            shutdown.addCloseTask(closeTask, true);
        }

        if (!quinoaDirectoryBuildItem.isDevServerMode(quinoaConfig.devServer)) {
            return null;
        }

        PackageManager packageManager = quinoaDir.get().getPackageManager();
        final int devServerPort = quinoaDirectoryBuildItem.getDevServerPort().getAsInt();
        final String checkPath = quinoaConfig.devServer.checkPath.orElse(null);
        if (!quinoaConfig.devServer.managed) {
            final String hostAddress = PackageManager.isDevServerUp(devServerHost, devServerPort, checkPath);
            if (hostAddress != null) {
                return new ForwardedDevServerBuildItem(hostAddress, devServerPort);
            } else {
                throw new IllegalStateException(
                        "The Web UI dev server (configured as not managed by Quinoa) is not started on port: " + devServerPort);
            }
        }

        StartupLogCompressor compressor = new StartupLogCompressor(
                (launchMode.isTest() ? "(test) " : "") + "Quinoa package manager live coding dev service starting:",
                consoleInstalled,
                loggingSetup,
                PROCESS_THREAD_PREDICATE);
        final AtomicReference<Process> dev = new AtomicReference<>();
        try {
            final int checkTimeout = quinoaConfig.devServer.checkTimeout;
            if (checkTimeout < 1000) {
                throw new ConfigurationException("quarkus.quinoa.dev-server.check-timeout must be greater than 1000ms");
            }
            final long start = Instant.now().toEpochMilli();

            final PackageManager.DevServer devServer = packageManager.dev(devServerCommand, devServerHost, devServerPort,
                    checkPath, checkTimeout);
            dev.set(devServer.process());
            compressor.close();
            final LiveCodingLogOutputFilter logOutputFilter = new LiveCodingLogOutputFilter(
                    quinoaConfig.devServer.logs);
            if (checkPath != null) {
                LOG.infof("Quinoa package manager live coding is up and running on port: %d (in %dms)",
                        devServerPort, Instant.now().toEpochMilli() - start);
            }
            final Closeable onClose = () -> {
                logOutputFilter.close();
                packageManager.stopDev(dev.get());
            };
            Map<String, String> devServerConfigMap = new LinkedHashMap<>();
            devServerConfigMap.put("quarkus.quinoa.dev-server.host", quinoaConfig.devServer.host);
            devServerConfigMap.put("quarkus.quinoa.dev-server.port", Integer.toString(devServerPort));
            devServerConfigMap.put("quarkus.quinoa.dev-server.check-timeout",
                    Integer.toString(quinoaConfig.devServer.checkTimeout));
            devServerConfigMap.put("quarkus.quinoa.dev-server.check-path", quinoaConfig.devServer.checkPath.orElse(""));
            devServerConfigMap.put("quarkus.quinoa.dev-server.managed", Boolean.toString(quinoaConfig.devServer.managed));
            devServerConfigMap.put("quarkus.quinoa.dev-server.logs", Boolean.toString(quinoaConfig.devServer.logs));
            devServerConfigMap.put("quarkus.quinoa.dev-server.websocket", Boolean.toString(quinoaConfig.devServer.websocket));
            devService = new DevServicesResultBuildItem.RunningDevService(
                    "quinoa-dev-server", null, onClose, devServerConfigMap);
            devServices.produce(devService.toBuildItem());
            return new ForwardedDevServerBuildItem(devServer.hostAddress(), devServerPort);
        } catch (Throwable t) {
            packageManager.stopDev(dev.get());
            compressor.closeAndDumpCaptured();
            throw new RuntimeException(t);
        }
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    @Record(RUNTIME_INIT)
    public void runtimeInit(
            QuinoaConfig quinoaConfig,
            QuinoaRecorder recorder,
            HttpBuildTimeConfig httpBuildTimeConfig,
            Optional<ForwardedDevServerBuildItem> devProxy,
            CoreVertxBuildItem vertx,
            BuildProducer<RouteBuildItem> routes,
            BuildProducer<WebsocketSubProtocolsBuildItem> websocketSubProtocols,
            BuildProducer<ResumeOn404BuildItem> resumeOn404) throws IOException {
        if (quinoaConfig.justBuild) {
            LOG.info("Quinoa is in build only mode");
            return;
        }
        if (quinoaConfig.isEnabled() && devProxy.isPresent()) {
            LOG.infof("Quinoa is forwarding unhandled requests to port: %d", devProxy.get().getPort());
            final QuinoaHandlerConfig handlerConfig = quinoaConfig.toHandlerConfig(false, httpBuildTimeConfig);
            routes.produce(RouteBuildItem.builder().orderedRoute("/*", QUINOA_ROUTE_ORDER)
                    .handler(recorder.quinoaProxyDevHandler(handlerConfig, vertx.getVertx(), devProxy.get().getHost(),
                            devProxy.get().getPort(),
                            quinoaConfig.devServer.websocket))
                    .build());
            if (quinoaConfig.devServer.websocket) {
                websocketSubProtocols.produce(new WebsocketSubProtocolsBuildItem("*"));
            }
            if (quinoaConfig.enableSPARouting) {
                resumeOn404.produce(new ResumeOn404BuildItem());
                routes.produce(RouteBuildItem.builder().orderedRoute("/*", QUINOA_SPA_ROUTE_ORDER)
                        .handler(recorder.quinoaSPARoutingHandler(handlerConfig))
                        .build());
            }
        }
    }

    private void shutdownDevService() {
        if (devService != null) {
            try {
                devService.close();
            } catch (Throwable e) {
                LOG.error("Failed to stop Quinoa package manager live coding", e);
            } finally {
                devService = null;
            }
        }
    }

    private static class LiveCodingLogOutputFilter implements Closeable, BiPredicate<String, Boolean> {
        private final ScheduledThreadPoolExecutor executor;
        private final Thread thread;
        private final List<String> buffer = Collections.synchronizedList(new ArrayList<>());
        private final boolean enableLogs;
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

        public LiveCodingLogOutputFilter(boolean enableLogs) {
            this.enableLogs = enableLogs;
            if (QuarkusConsole.INSTANCE.isAnsiSupported()) {
                executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1,
                        new LiveCodingLoggingThreadFactory());
                executor.setRemoveOnCancelPolicy(true);
                QuarkusConsole.installRedirects();
                this.thread = Thread.currentThread();
                QuarkusConsole.addOutputFilter(this);
            } else {
                executor = null;
                thread = null;
            }
        }

        @Override
        public void close() {
            if (thread == null) {
                return;
            }
            QuarkusConsole.removeOutputFilter(this);
            executor.shutdown();
        }

        @Override
        public boolean test(final String s, final Boolean err) {
            Thread current = Thread.currentThread();
            if (PROCESS_THREAD_PREDICATE.test(current) && !err) {
                if (!enableLogs) {
                    return false;
                }
                buffer.add(s.replaceAll("\\x1b\\[[0-9;]*[a-zA-Z]", ""));
                scheduled.getAndUpdate(scheduledFuture -> {
                    if (scheduledFuture != null && !scheduledFuture.isDone()) {
                        scheduledFuture.cancel(true);
                    }
                    return executor.schedule(() -> {
                        if (!buffer.isEmpty()) {
                            LOG.infof("\u001b[33mQuinoa package manager live coding server has spoken: " + RESET
                                    + " \n%s", join("", buffer));
                            buffer.clear();
                        }
                    }, 200, TimeUnit.MILLISECONDS);
                });
                return false;
            }
            return true;
        }

        static class LiveCodingLoggingThreadFactory implements ThreadFactory {
            public Thread newThread(Runnable r) {
                return new Thread(r, "Live coding server");
            }
        }

    }

}
