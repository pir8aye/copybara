/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.transform.metadata;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.copybara.ChangeVisitable;
import com.google.copybara.ChangeVisitable.VisitResult;
import com.google.copybara.exception.RepoException;
import com.google.copybara.TransformWork;
import com.google.copybara.Transformation;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.transform.ExplicitReversal;
import com.google.copybara.transform.IntentionalNoop;
import com.google.copybara.transform.RegexTemplateTokens;
import com.google.copybara.transform.RegexTemplateTokens.Replacer;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.re2j.Pattern;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Adjusts textual references in  change messages to match the destination.
 *
 */
public class ReferenceMigrator implements Transformation {


  static final int MAX_CHANGES_TO_VISIT = 5000;

  private final RegexTemplateTokens before;
  private final RegexTemplateTokens after;
  private final ImmutableList<String> additionalLabels;

  @Nullable private final Pattern reversePattern;

  private final Map<String, String> knownChanges = new HashMap<>();

  ReferenceMigrator(
      RegexTemplateTokens before,
      RegexTemplateTokens after,
      Pattern reversePattern,
      ImmutableList<String> additionalLabels) {
    this.before = checkNotNull(before, "before");
    this.after = checkNotNull(after, "after");
    this.additionalLabels = checkNotNull(additionalLabels, "additionalLabels");
    this.reversePattern = reversePattern;
  }

  public static ReferenceMigrator create(
      String before, String after, Pattern forward, @Nullable Pattern backward,
      ImmutableList<String> additionalLabels, Location location) throws EvalException {
    Map<String, Pattern> patterns = ImmutableMap.of("reference", forward);
    RegexTemplateTokens beforeTokens =
        new RegexTemplateTokens(location, before, patterns, /* repeatedGroups= */ false);
    beforeTokens.validateUnused();
    RegexTemplateTokens afterTokens =
        new RegexTemplateTokens(location, after, patterns, /* repeatedGroups= */ false);
    afterTokens.validateUnused();
    if (after.lastIndexOf("$1") != -1) {
      // TODO: Handle escaping
      throw new EvalException(location,
          String.format("Destination format '%s' uses the reserved token '$1'.", after));
    }
    return new ReferenceMigrator(beforeTokens, afterTokens, backward, additionalLabels);
  }

  @Override
  public void transform(TransformWork work) throws ValidationException {
    AtomicReference<ValidationException> thrown = new AtomicReference<>();
    Replacer replacer = before.callbackReplacer(after, (groupValues, template) -> {
        if (groupValues.get(0) != null) {
          try {
            String destinationRef = findChange(groupValues.get(1),
                work.getMigrationInfo().getOriginLabel(),
                work.getMigrationInfo().destinationVisitable());
            if (destinationRef != null) {
              // This will not work for the case where the template was "foo\\$1", if this is an
              // issue, a non-naive implementation might be required.
              return Pattern.compile("[$]1").matcher(template).replaceAll(destinationRef);
            } else {
              return groupValues.get(0);
            }
          } catch (ValidationException exception) {
            thrown.compareAndSet(null, exception);
            return groupValues.get(0);
          }
        }
        return template;
      }, false, false, null);
    String replaced = replacer.replace(work.getMessage());
    if (thrown.get() != null) {
      throw thrown.get();
    }
    if (!replaced.equals(work.getMessage())) {
      work.setMessage(replaced);
    }
  }

  @Override
  public Transformation reverse() {
    return new ExplicitReversal(IntentionalNoop.INSTANCE, this);
  }

  @Override
  public String describe() {
    return "map_references: " + before + " to " + after;
  }

  @Nullable
  private String findChange(String refBeingMigrated,
      String originLabel,
      ChangeVisitable<?> destinationReader) throws  ValidationException {
    AtomicInteger changesVisited = new AtomicInteger(0);
    ImmutableList<String> originLabels =
        ImmutableList.<String>builder().add(originLabel).addAll(additionalLabels).build();
    if (destinationReader == null) {
      throw new ValidationException("Destination does not support reading change history.");
    }
    if (knownChanges.containsKey(refBeingMigrated)) {
      return knownChanges.get(refBeingMigrated);
    } else {
      try {
        destinationReader.visitChangesWithAnyLabel(null, originLabels, (input, labels) -> {
          for (String labelValue : labels.values()) {
              knownChanges.putIfAbsent(labelValue, input.refAsString());
              if (labelValue.equals(refBeingMigrated)) {
                return VisitResult.TERMINATE;
              }
          }
          if (changesVisited.incrementAndGet() > MAX_CHANGES_TO_VISIT) {
            return VisitResult.TERMINATE;
          } else {
            return VisitResult.CONTINUE;
          }
        });
        String retVal = knownChanges.get(refBeingMigrated);
        if (reversePattern != null && retVal != null && !reversePattern.matches(retVal)) {
          throw new ValidationException(
              "Reference %s does not match regex '%s'", retVal, reversePattern);
        }
        return retVal;
      } catch (RepoException exception) {
        throw new ValidationException(exception, "Exception finding reference.");
      }
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("before", before)
        .add("after", after)
        .toString();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ReferenceMigrator
        && Objects.equal(this.before,
        ((ReferenceMigrator) other).before)
        && Objects.equal(this.after,
        ((ReferenceMigrator) other).after);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(before, after);
  }
}
