/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scanner.postjob;

import org.sonar.api.batch.AnalysisMode;
import org.sonar.api.batch.postjob.PostJobContext;
import org.sonar.api.batch.postjob.issue.PostJobIssue;
import org.sonar.api.config.Configuration;

public class DefaultPostJobContext implements PostJobContext {

  private final Configuration config;
  private final AnalysisMode analysisMode;

  public DefaultPostJobContext(Configuration config, AnalysisMode analysisMode) {
    this.config = config;
    this.analysisMode = analysisMode;
  }

  @Override
  public Configuration config() {
    return config;
  }

  @Override
  public AnalysisMode analysisMode() {
    return analysisMode;
  }

  @Override
  public Iterable<PostJobIssue> issues() {
    throw new UnsupportedOperationException("Preview mode was dropped.");
  }

  @Override
  public Iterable<PostJobIssue> resolvedIssues() {
    throw new UnsupportedOperationException("Preview mode was dropped.");
  }

}
