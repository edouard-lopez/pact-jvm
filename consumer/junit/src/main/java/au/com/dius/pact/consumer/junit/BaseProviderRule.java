package au.com.dius.pact.consumer.junit;

import au.com.dius.pact.consumer.ConsumerPactBuilder;
import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactDslRequestWithoutPath;
import au.com.dius.pact.consumer.dsl.PactDslResponse;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.BasePact;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.annotations.PactDirectory;
import au.com.dius.pact.core.model.annotations.PactFolder;
import au.com.dius.pact.core.support.Json;
import au.com.dius.pact.core.support.MetricEvent;
import au.com.dius.pact.core.support.Metrics;
import au.com.dius.pact.core.support.expressions.DataType;
import org.apache.commons.lang3.StringUtils;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import au.com.dius.pact.core.support.expressions.ExpressionParser;

import static au.com.dius.pact.consumer.ConsumerPactRunnerKt.runConsumerTest;

public class BaseProviderRule extends ExternalResource {

  protected final String provider;
  protected final Object target;
  protected MockProviderConfig config;
  private Map<String, BasePact> pacts;
  private MockServer mockServer;
  private final ExpressionParser ep;

  public BaseProviderRule(Object target, String provider, String hostInterface, Integer port, PactSpecVersion pactVersion) {
    this.target = target;
    this.provider = provider;
    config = MockProviderConfig.httpConfig(StringUtils.isEmpty(hostInterface) ? MockProviderConfig.LOCALHOST : hostInterface,
      port == null ? 0 : port, pactVersion, MockServerImplementation.Default);
    ep = new ExpressionParser();
  }

  public MockProviderConfig getConfig() {
      return config;
  }

  public MockServer getMockServer() {
    return mockServer;
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {

      @Override
      public void evaluate() throws Throwable {
        PactVerifications pactVerifications = description.getAnnotation(PactVerifications.class);
        if (pactVerifications != null) {
          evaluatePactVerifications(pactVerifications, base);
          return;
        }

        PactVerification pactDef = description.getAnnotation(PactVerification.class);
        // no pactVerification? execute the test normally
        if (pactDef == null) {
          base.evaluate();
          return;
        }

        Map<String, BasePact> pacts = getPacts(pactDef.fragment());
        Optional<BasePact> pact;
        if (pactDef.value().length == 1 && StringUtils.isEmpty(pactDef.value()[0])) {
          pact = pacts.values().stream().findFirst();
        } else {
          pact = Arrays.stream(pactDef.value()).map(pacts::get)
            .filter(Objects::nonNull).findFirst();
        }
        if (pact.isEmpty()) {
          base.evaluate();
          return;
        }

        if (config.getPactVersion() == PactSpecVersion.V4) {
          pact.get().asV4Pact().component1().getInteractions()
            .forEach(i -> i.getComments().put("testname", Json.toJson(description.getDisplayName())));
        }

        PactFolder pactFolder = target.getClass().getAnnotation(PactFolder.class);
        PactDirectory pactDirectory = target.getClass().getAnnotation(PactDirectory.class);
        BasePact basePact = pact.get();
        Metrics.INSTANCE.sendMetrics(new MetricEvent.ConsumerTestRun(basePact.getInteractions().size(), "junit"));
        PactVerificationResult result = runPactTest(base, basePact, pactFolder, pactDirectory);
        validateResult(result, pactDef);
      }
    };
  }

  private void evaluatePactVerifications(PactVerifications pactVerifications, Statement base) throws Throwable {
    List<PactVerification> possiblePactVerifications = findPactVerifications(pactVerifications, this.provider);
    if (possiblePactVerifications.isEmpty()) {
        base.evaluate();
        return;
    }

    final BasePact[] pact = { null };
    possiblePactVerifications.forEach(pactVerification -> {
      Optional<Method> possiblePactMethod = findPactMethod(pactVerification);
      if (possiblePactMethod.isEmpty()) {
        throw new UnsupportedOperationException("Could not find method with @Pact for the provider " + provider);
      }

      Method method = possiblePactMethod.get();
      Pact pactAnnotation = method.getAnnotation(Pact.class);
      PactDslWithProvider dslBuilder = ConsumerPactBuilder.consumer(
              ep.parseExpression(pactAnnotation.consumer(), DataType.RAW).toString())
        .pactSpecVersion(config.getPactVersion())
        .hasPactWith(provider);
      updateAnyDefaultValues(dslBuilder);
      try {
        BasePact pactFromMethod = (BasePact) method.invoke(target, dslBuilder);
        if (pact[0] == null) {
          pact[0] = pactFromMethod;
        } else {
          pact[0].mergeInteractions(pactFromMethod.getInteractions());
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to invoke pact method", e);
      }
    });

    PactFolder pactFolder = target.getClass().getAnnotation(PactFolder.class);
    PactDirectory pactDirectory = target.getClass().getAnnotation(PactDirectory.class);
    PactVerificationResult result = runPactTest(base, pact[0], pactFolder, pactDirectory);
    JUnitTestSupport.validateMockServerResult(result);
  }

  private List<PactVerification> findPactVerifications(PactVerifications pactVerifications, String providerName) {
      PactVerification[] pactVerificationValues = pactVerifications.value();
      return Arrays.stream(pactVerificationValues).filter(p -> {
          String[] providers = p.value();
          if (providers.length != 1) {
              throw new IllegalArgumentException(
                      "Each @PactVerification must specify one and only provider when using @PactVerifications");
          }
          String provider = providers[0];
          return StringUtils.equals(provider, providerName);
      }).collect(Collectors.toList());
  }

  private Optional<Method> findPactMethod(PactVerification pactVerification) {
      String pactFragment = pactVerification.fragment();
      for (Method method : target.getClass().getMethods()) {
          Pact pact = method.getAnnotation(Pact.class);
          if (pact != null && provider.equals(ep.parseExpression(pact.provider(), DataType.RAW).toString())
                  && (pactFragment.isEmpty() || pactFragment.equals(method.getName()))) {

              validatePactSignature(method);
              return Optional.of(method);
          }
      }
      return Optional.empty();
  }

  private void validatePactSignature(Method method) {
      boolean hasValidPactSignature =
        RequestResponsePact.class.isAssignableFrom(method.getReturnType())
                      && method.getParameterTypes().length == 1
                      && method.getParameterTypes()[0].isAssignableFrom(PactDslWithProvider.class);

      if (!hasValidPactSignature) {
          throw new UnsupportedOperationException("Method " + method.getName() +
              " does not conform required method signature 'public RequestResponsePact xxx(PactDslWithProvider builder)'");
      }
  }

  private PactVerificationResult runPactTest(final Statement base, BasePact pact, PactFolder pactFolder, PactDirectory pactDirectory) {
    return runConsumerTest(pact, config, (mockServer, context) -> {
      this.mockServer = mockServer;
      base.evaluate();
      this.mockServer = null;

      if (pactFolder != null) {
        context.setPactFolder(pactFolder.value());
      }
      if (pactDirectory != null) {
        context.setPactFolder(pactDirectory.value());
      }

      return null;
    });
  }

  protected void validateResult(PactVerificationResult result, PactVerification pactVerification) throws Throwable {
    JUnitTestSupport.validateMockServerResult(result);
  }

  /**
   * scan all methods for @Pact annotation and execute them, if not already initialized
   * @param fragment
   */
  protected Map<String, BasePact> getPacts(String fragment) {
    if (pacts == null) {
      pacts = new HashMap<>();
      for (Method m: target.getClass().getMethods()) {
        if (JUnitTestSupport.conformsToSignature(m, config.getPactVersion()) && methodMatchesFragment(m, fragment)) {
          Pact pactAnnotation = m.getAnnotation(Pact.class);
          String provider = ep.parseExpression(pactAnnotation.provider(), DataType.RAW).toString();
          if (StringUtils.isEmpty(provider) || this.provider.equals(provider)) {
            PactDslWithProvider dslBuilder = ConsumerPactBuilder.consumer(
                ep.parseExpression(pactAnnotation.consumer(), DataType.RAW).toString())
              .pactSpecVersion(config.getPactVersion())
              .hasPactWith(this.provider);
            updateAnyDefaultValues(dslBuilder);
            try {
              BasePact pact = (BasePact) m.invoke(target, dslBuilder);
              pacts.put(this.provider, pact);
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke pact method", e);
            }
          }
        }
      }
    }
    return pacts;
  }

  private void updateAnyDefaultValues(PactDslWithProvider dslBuilder) {
    for (Method m: target.getClass().getMethods()) {
      if (m.isAnnotationPresent(DefaultRequestValues.class)) {
        setupDefaultRequestValues(dslBuilder, m);
      } else if (m.isAnnotationPresent(DefaultResponseValues.class)) {
        setupDefaultResponseValues(dslBuilder, m);
      }
    }
  }

  private void setupDefaultRequestValues(PactDslWithProvider dslBuilder, Method m) {
    if (m.getParameterTypes().length == 1
      && m.getParameterTypes()[0].isAssignableFrom(PactDslRequestWithoutPath.class)) {
      PactDslRequestWithoutPath defaults = dslBuilder.uponReceiving("defaults");
      try {
        m.invoke(target, defaults);
      } catch (IllegalAccessException| InvocationTargetException e) {
        throw new RuntimeException("Failed to invoke default request method", e);
      }
      dslBuilder.setDefaultRequestValues(defaults);
    } else {
      throw new UnsupportedOperationException("Method " + m.getName() +
        " does not conform required method signature 'public void " + m.getName() +
        "(PactDslRequestWithoutPath defaultRequest)'");
    }
  }

  private void setupDefaultResponseValues(PactDslWithProvider dslBuilder, Method m) {
    if (m.getParameterTypes().length == 1
      && m.getParameterTypes()[0].isAssignableFrom(PactDslResponse.class)) {
      PactDslResponse defaults = new PactDslResponse(dslBuilder.getConsumerPactBuilder(), null, null, null);
      try {
        m.invoke(target, defaults);
      } catch (IllegalAccessException| InvocationTargetException e) {
        throw new RuntimeException("Failed to invoke default response method", e);
      }
      dslBuilder.setDefaultResponseValues(defaults);
    } else {
      throw new UnsupportedOperationException("Method " + m.getName() +
        " does not conform required method signature 'public void " + m.getName() +
        "(PactDslResponse defaultResponse)'");
    }
  }

  private boolean methodMatchesFragment(Method m, String fragment) {
      return StringUtils.isEmpty(fragment) || m.getName().equals(fragment);
  }

  /**
   * Returns the URL for the mock server. Returns null if the mock server is not running.
   * @return String URL or null if mock server not running
   */
  public String getUrl() {
    return mockServer == null ? null : mockServer.getUrl();
  }

  /**
   * Returns the port number for the mock server. Returns null if the mock server is not running.
   * @return port number or null if mock server not running
   */
  public Integer getPort() {
    return mockServer == null ? null : mockServer.getPort();
  }
}
