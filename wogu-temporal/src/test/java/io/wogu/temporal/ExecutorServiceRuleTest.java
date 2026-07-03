package io.wogu.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import io.wogu.api.CallPathFrame;
import io.wogu.api.RuleResult;
import io.wogu.api.ValidationContext;
import io.wogu.api.ValidatorRunOutcome;
import io.wogu.api.Violation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Integration-style tests for WG010, exercised through {@link TemporalWorkflowValidator}. */
class ExecutorServiceRuleTest {

  @TempDir Path sourceRoot;

  private final TemporalWorkflowValidator validator = new TemporalWorkflowValidator();

  private void writeJavaFile(String relativePath, String content) throws IOException {
    Path file = sourceRoot.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private ValidationContext context() {
    return new TestValidationContext("test-project", sourceRoot, List.of(sourceRoot));
  }

  private RuleResult wg010Result() {
    ValidatorRunOutcome outcome = validator.validate(context());
    return outcome.ruleResults().stream()
        .filter(result -> result.rule().id().equals("WG010"))
        .findFirst()
        .orElseThrow();
  }

  private void writeWorkflowInterface() throws IOException {
    writeJavaFile(
        "com/example/PaymentWorkflow.java",
        """
        package com.example;

        import io.temporal.workflow.WorkflowInterface;
        import io.temporal.workflow.WorkflowMethod;

        @WorkflowInterface
        public interface PaymentWorkflow {
          @WorkflowMethod
          void process();
        }
        """);
  }

  @Test
  void flagsExecutorsNewFixedThreadPoolDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        import java.util.concurrent.ExecutorService;
        import java.util.concurrent.Executors;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            ExecutorService pool = Executors.newFixedThreadPool(4);
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.passed()).isFalse();
    assertThat(wg010.violations()).hasSize(1);
    Violation violation = wg010.violations().get(0);
    assertThat(violation.rule().id()).isEqualTo("WG010");
    assertThat(violation.className()).isEqualTo("PaymentWorkflowImpl");
    assertThat(violation.message()).contains("must not create or manage Java threads");
    assertThat(violation.suggestedFix()).contains("Async");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "Executors.newFixedThreadPool()");
  }

  @Test
  void flagsConstructingAThreadDirectlyInsideAWorkflowImplementation() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          @Override
          public void process() {
            Thread thread = new Thread(() -> {});
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.violations()).hasSize(1);
    Violation violation = wg010.violations().get(0);
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly("PaymentWorkflowImpl.process()", "new Thread()");
  }

  @Test
  void flagsCompletableFutureSupplyAsyncInsideAServiceCalledByTheWorkflow() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final PaymentService paymentService = new PaymentService();

          @Override
          public void process() {
            paymentService.chargeAsync();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentService.java",
        """
        package com.example;

        import java.util.concurrent.CompletableFuture;

        public class PaymentService {
          void chargeAsync() {
            CompletableFuture.supplyAsync(() -> "done");
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.violations()).hasSize(1);
    Violation violation = wg010.violations().get(0);
    assertThat(violation.className()).isEqualTo("PaymentService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "PaymentService.chargeAsync()", "CompletableFuture.supplyAsync()");
  }

  @Test
  void flagsForkJoinPoolCommonPoolReachableThroughANestedServiceCall() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private final OrderService orderService = new OrderService();

          @Override
          public void process() {
            orderService.createOrder();
          }
        }
        """);
    writeJavaFile(
        "com/example/OrderService.java",
        """
        package com.example;

        public class OrderService {
          private final PoolService poolService = new PoolService();

          void createOrder() {
            poolService.pool();
          }
        }
        """);
    writeJavaFile(
        "com/example/PoolService.java",
        """
        package com.example;

        import java.util.concurrent.ForkJoinPool;

        public class PoolService {
          ForkJoinPool pool() {
            return ForkJoinPool.commonPool();
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.violations()).hasSize(1);
    Violation violation = wg010.violations().get(0);
    assertThat(violation.className()).isEqualTo("PoolService");
    assertThat(violation.callPath())
        .extracting(CallPathFrame::displayName)
        .containsExactly(
            "PaymentWorkflowImpl.process()", "OrderService.createOrder()", "PoolService.pool()", "ForkJoinPool.commonPool()");
  }

  @Test
  void doesNotFlagThreadCreationInsideAnActivityImplementationInvokedThroughItsInterface() throws IOException {
    writeWorkflowInterface();
    writeJavaFile(
        "com/example/PaymentWorkflowImpl.java",
        """
        package com.example;

        public class PaymentWorkflowImpl implements PaymentWorkflow {
          private PaymentActivity activity;

          @Override
          public void process() {
            activity.chargeCard();
          }
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivity.java",
        """
        package com.example;

        public interface PaymentActivity {
          void chargeCard();
        }
        """);
    writeJavaFile(
        "com/example/PaymentActivityImpl.java",
        """
        package com.example;

        public class PaymentActivityImpl implements PaymentActivity {
          @Override
          public void chargeCard() {
            Thread thread = new Thread(() -> {});
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.passed()).isTrue();
    assertThat(wg010.violations()).isEmpty();
  }

  @Test
  void doesNotFlagThreadCreationOutsideAWorkflowImplementation() throws IOException {
    writeJavaFile(
        "com/example/PlainWorkerPool.java",
        """
        package com.example;

        import java.util.concurrent.ExecutorService;
        import java.util.concurrent.Executors;

        public class PlainWorkerPool {
          public ExecutorService create() {
            return Executors.newCachedThreadPool();
          }
        }
        """);

    RuleResult wg010 = wg010Result();

    assertThat(wg010.passed()).isTrue();
    assertThat(wg010.violations()).isEmpty();
  }
}
