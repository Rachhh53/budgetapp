package app.budget.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** What the desktop shell is told over stdout about the tray and Start with Windows. */
class ShellLinkTest {

    private final AtomicBoolean phoneAccessOn = new AtomicBoolean();
    private final AtomicBoolean startWithWindows = new AtomicBoolean();
    private final List<String> sent = new ArrayList<>();
    private final ShellLink link = new ShellLink(phoneAccessOn::get, startWithWindows::get);

    @Test
    void saysNothingUntilTheShellIsListening() {
        phoneAccessOn.set(true);
        link.update();

        assertThat(sent).isEmpty();
    }

    @Test
    void sendsTheStateOnceConnectedThenOnlyWhenItChanges() {
        link.connect(sent::add);
        link.update();
        phoneAccessOn.set(true);
        link.update();
        link.update();
        startWithWindows.set(true);
        link.update();

        assertThat(sent).containsExactly(
                "SHELL keep_in_tray=false start_with_windows=false",
                "SHELL keep_in_tray=true start_with_windows=false",
                "SHELL keep_in_tray=true start_with_windows=true");
    }
}
