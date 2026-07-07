package io.quarkus.gradle.application.internal.dev;

import static io.quarkus.deployment.dev.BuildOutputChangeKind.MODIFIED;
import static io.quarkus.deployment.dev.BuildOutputChangeStatus.BUILD_SUCCEEDED;
import static io.quarkus.gradle.application.internal.dev.BuildOutputChangesPolicy.Outcome.BASELINE_DROPPED;
import static io.quarkus.gradle.application.internal.dev.BuildOutputChangesPolicy.Outcome.PENDING;
import static io.quarkus.gradle.application.internal.dev.BuildOutputChangesPolicy.Outcome.RESTART_REQUIRED;
import static io.quarkus.gradle.application.internal.dev.BuildOutputChangesPolicy.Outcome.SENT_APPLIED;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.deployment.dev.BuildOutputChanges;
import io.quarkus.deployment.dev.BuildOutputChangesApplyStatus;
import io.quarkus.deployment.dev.BuildOutputPathChange;

class QuarkusApplicationDevSessionTest {

    @Test
    void baselineBeforeReadyDoesNotCreateReloadBatch() {
        var session = new QuarkusApplicationDevSession();

        var result = session.accept(changes(1, "org/acme/App.class"));

        assertThat(result.outcome()).isEqualTo(BASELINE_DROPPED);
        assertThat(session.deliver(ignored -> BuildOutputChangesApplyStatus.APPLIED).outcome())
                .isEqualTo(BuildOutputChangesPolicy.Outcome.NOTHING_TO_SEND);
    }

    @Test
    void readySessionAcceptsAndDeliversReloadableChanges() {
        var session = new QuarkusApplicationDevSession();
        session.markReady();

        var result = session.accept(changes(1, "org/acme/App.class"));
        var delivery = session.deliver(ignored -> BuildOutputChangesApplyStatus.APPLIED);

        assertThat(result.outcome()).isEqualTo(PENDING);
        assertThat(delivery.outcome()).isEqualTo(SENT_APPLIED);
    }

    @Test
    void restartRequiredDoesNotErasePendingChanges() {
        var session = new QuarkusApplicationDevSession();
        session.markReady();
        session.accept(changes(1, "org/acme/App.class"));

        var restart = session.acceptRestartRequired(2);
        var delivery = session.deliver(ignored -> BuildOutputChangesApplyStatus.APPLIED);

        assertThat(restart.outcome()).isEqualTo(RESTART_REQUIRED);
        assertThat(delivery.changes().sequence()).isEqualTo(1);
    }

    private static BuildOutputChanges changes(long sequence, String relativePath) {
        Path outputRoot = Path.of("build/classes/java/main");
        return new BuildOutputChanges(sequence, BUILD_SUCCEEDED,
                List.of(new BuildOutputPathChange(outputRoot, outputRoot.resolve(relativePath), MODIFIED)),
                null, null, null, null, null, false, false);
    }
}
