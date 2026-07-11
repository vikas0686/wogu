/**
 * Maven integration for WoGu.
 *
 * <p>{@link dev.wogu.maven.ValidateMojo} implements the {@code wogu:validate} goal, bound
 * by default to the {@code verify} phase. It adapts the current {@code MavenProject} into
 * a {@link dev.wogu.api.ValidationContext} and delegates to {@link dev.wogu.maven.WoguRunner},
 * which contains the actual discover-run-report sequence and has no dependency on the
 * Maven API.
 */
package dev.wogu.maven;
