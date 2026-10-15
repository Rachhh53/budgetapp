package app.budget.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsToLoopbackAndTheAppDataFolder() {
        Config config = Config.fromEnvironment(Map.of("APPDATA", "C:\\Users\\Amara\\AppData\\Roaming"));

        assertThat(config.bindHost()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(4873);
        assertThat(config.dataDir()).isEqualTo(Path.of("C:\\Users\\Amara\\AppData\\Roaming", "Budget"));
        assertThat(config.parentPid()).isEmpty();
        assertThat(config.shutdownToken()).isEmpty();
        assertThat(config.allowedHosts()).isEmpty();
    }

    @Test
    void usesWindowsOwnFoldersForFontsAndDownloads() {
        Config config = Config.fromEnvironment(
                Map.of("WINDIR", "C:\\Windows", "USERPROFILE", "C:\\Users\\Amara"),
                Config.Platform.of("Windows 11", "C:\\Users\\Amara"));

        assertThat(config.fontsDir()).hasValue(Path.of("C:\\Windows", "Fonts"));
        assertThat(config.downloadsDir()).hasValue(Path.of("C:\\Users\\Amara", "Downloads"));
    }

    @Test
    void usesAMacsOwnFoldersWhenNothingIsSet() {
        Config config = Config.fromEnvironment(Map.of(), Config.Platform.of("Mac OS X", "/Users/amara"));

        assertThat(config.dataDir()).isEqualTo(Path.of("/Users/amara", "Library", "Application Support", "Budget"));
        assertThat(config.fontsDir()).hasValue(Config.MAC_FONTS);
        assertThat(config.downloadsDir()).hasValue(Path.of("/Users/amara", "Downloads"));
    }

    @Test
    void leavesFontsAndDownloadsEmptyElsewhere() {
        Config config = Config.fromEnvironment(Map.of(), Config.Platform.of("Linux", "/home/amara"));

        assertThat(config.dataDir()).isEqualTo(Path.of("/home/amara", ".local", "share", "Budget"));
        assertThat(config.fontsDir()).isEmpty();
        assertThat(config.downloadsDir()).isEmpty();
    }

    @Test
    void readsEveryValueFromTheEnvironment() {
        Config config = Config.fromEnvironment(Map.of(
                "BUDGET_BIND_HOST", "100.64.0.7",
                "BUDGET_PORT", "5000",
                "BUDGET_DATA_DIR", "data",
                "BUDGET_PARENT_PID", "4242",
                "BUDGET_SHUTDOWN_TOKEN", "secret",
                "BUDGET_ALLOWED_HOSTS", "Desk.tailnet.ts.net, , other.example"));

        assertThat(config.bindHost()).isEqualTo("100.64.0.7");
        assertThat(config.port()).isEqualTo(5000);
        assertThat(config.dataDir()).isEqualTo(Path.of("data"));
        assertThat(config.parentPid()).hasValue(4242L);
        assertThat(config.shutdownToken()).hasValue("secret");
        assertThat(config.allowedHosts()).containsExactlyInAnyOrder("desk.tailnet.ts.net", "other.example");
    }

    @Test
    void explainsABadNumberWithoutEchoingIt() {
        assertThatThrownBy(() -> Config.fromEnvironment(Map.of("BUDGET_PORT", "hunter2")))
                .isInstanceOf(Config.InvalidConfigException.class)
                .hasMessageContaining("BUDGET_PORT")
                .hasMessageNotContaining("hunter2");
        assertThatThrownBy(() -> Config.fromEnvironment(Map.of("BUDGET_PORT", "70000")))
                .isInstanceOf(Config.InvalidConfigException.class);
    }
}
