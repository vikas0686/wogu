/**
 * Maven integration for WoGu.
 *
 * <p>{@link io.wogu.maven.ValidateMojo} implements the {@code wogu:validate} goal, bound
 * by default to the {@code verify} phase. It adapts the current {@code MavenProject} into
 * a {@link io.wogu.api.ValidationContext} and delegates to {@link io.wogu.maven.WoguRunner},
 * which contains the actual discover-run-report sequence and has no dependency on the
 * Maven API.
 */
package io.wogu.maven;
