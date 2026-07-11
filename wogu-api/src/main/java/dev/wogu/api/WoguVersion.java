package dev.wogu.api;

/**
 * Resolves the running WoGu version for display in reports and console output.
 */
public final class WoguVersion {

  private static final String DEVELOPMENT_VERSION = "development";

  private WoguVersion() {}

  /**
   * The WoGu version currently executing, read from this jar's manifest.
   *
   * @return the {@code Implementation-Version} manifest entry, or {@code "development"}
   *     when running from unpackaged classes (e.g. inside an IDE or test run) where no
   *     manifest is present
   */
  public static String current() {
    String version = WoguVersion.class.getPackage().getImplementationVersion();
    return version == null ? DEVELOPMENT_VERSION : version;
  }
}
