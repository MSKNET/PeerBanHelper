package com.ghostchu.peerbanhelper.btn;

import com.ghostchu.peerbanhelper.Main;
import com.ghostchu.peerbanhelper.PeerBanHelperServer;
import com.ghostchu.peerbanhelper.text.Lang;
import com.ghostchu.peerbanhelper.util.JsonUtil;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bspfsystems.yamlconfiguration.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BtnManager {
    @Getter
    private final PeerBanHelperServer server;
    private final String configUrl;
    @Getter
    private final BtnNetwork network;
    private final boolean submit;
    private final String appId;
    private final String appSecret;
    private ScheduledExecutorService executeService = Executors.newScheduledThreadPool(1);
    @Getter
    private BtnConfig btnConfig;
    @Getter
    private Methanol httpClient;

    @SneakyThrows(IOException.class)
    public BtnManager(PeerBanHelperServer server, ConfigurationSection section) {
        this.server = server;
        if (!section.getBoolean("enabled")) {
            throw new IllegalStateException("BTN has been disabled");
        }
        this.configUrl = section.getString("config-url");
        this.submit = section.getBoolean("submit");
        this.appId = section.getString("app-id");
        this.appSecret = section.getString("app-secret");
        this.network = new BtnNetwork(this, appId, appSecret, submit);
        File file = new File(Main.getDataDirectory(), "btn.cache");
        if (!file.exists()) {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            file.createNewFile();
        } else {
            try {
                this.network.setRule(JsonUtil.getGson().fromJson(Files.readString(file.toPath()), BtnRule.class));
            } catch (Throwable ignored) {
            }
        }
        setupHttpClient();
        reconfigureExecutor();
    }

    private void setupHttpClient() {
        CookieManager cm = new CookieManager();
        cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = Methanol
                .newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .userAgent(Main.getUserAgent())
                .defaultHeader("User-Agent", Main.getUserAgent())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("BTN-AppID", appId)
                .defaultHeader("BTN-AppSecret", appSecret)
                .connectTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .headersTimeout(Duration.of(30, ChronoUnit.SECONDS))
                .readTimeout(Duration.of(60, ChronoUnit.SECONDS))
                .cookieHandler(cm).build();
    }

    private void reconfigureExecutor() {
        try {
            HttpResponse<String> resp = httpClient.send(MutableRequest.GET(configUrl),HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn(Lang.BTN_CONFIG_FAILS, resp.statusCode() + " - " + resp.body());
                return;
            }
            BtnConfig btnConfig = JsonUtil.getGson().fromJson(resp.body(), BtnConfig.class);
            if (btnConfig.equals(this.btnConfig)) {
                return;
            }
            if (!executeService.isShutdown()) {
                executeService.shutdownNow();
            }
            executeService = Executors.newScheduledThreadPool(2);
            Random random = new Random();
            long ruleUpdateOffset = random.nextLong(btnConfig.getThreshold().getDelayRandomRange());
            long pingOffset = random.nextLong(btnConfig.getThreshold().getDelayRandomRange());
            executeService.scheduleAtFixedRate(network::updateRule, ruleUpdateOffset, btnConfig.getThreshold().getRuleUpdatePeriod(), TimeUnit.MILLISECONDS);
            executeService.scheduleAtFixedRate(network::submit, pingOffset, btnConfig.getThreshold().getSubmitPeriod(), TimeUnit.MILLISECONDS);
            log.info(Lang.BTN_NETWORK_RECONFIGURED, btnConfig);
            this.btnConfig = btnConfig;
        } catch (Throwable e) {
            log.warn(Lang.BTN_CONFIG_FAILS, e);
        }
    }

    public void close() {
        executeService.shutdownNow();
    }


}