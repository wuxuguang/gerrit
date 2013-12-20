// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.change;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.DeleteReviewer.Input;
import com.google.gerrit.server.index.ChangeIndexer;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.util.TimeUtil;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class DeleteReviewer implements RestModifyView<ReviewerResource, Input> {
  public static class Input {
  }

  private final Provider<ReviewDb> dbProvider;
  private final ChangeIndexer indexer;
  private final IdentifiedUser.GenericFactory userFactory;

  @Inject
  DeleteReviewer(Provider<ReviewDb> dbProvider, ChangeIndexer indexer,
      IdentifiedUser.GenericFactory userFactory) {
    this.dbProvider = dbProvider;
    this.indexer = indexer;
    this.userFactory = userFactory;
  }

  @Override
  public Response<?> apply(ReviewerResource rsrc, Input input)
      throws AuthException, ResourceNotFoundException, OrmException,
      IOException {
    ChangeControl control = rsrc.getControl();
    Change.Id changeId = rsrc.getChange().getId();
    ReviewDb db = dbProvider.get();
    StringBuilder msg = new StringBuilder();
    db.changes().beginTransaction(changeId);
    try {
      List<PatchSetApproval> del = Lists.newArrayList();
      for (PatchSetApproval a : approvals(db, rsrc)) {
        if (control.canRemoveReviewer(a)) {
          del.add(a);
          if (a.getValue() != 0) {
            if (msg.length() == 0) {
              msg.append("Removed the following approvals:\n\n");
            }
            msg.append("* ")
                .append(a.getLabel()).append(formatLabelValue(a.getValue()))
                .append(" by ").append(userFactory.create(a.getAccountId()).getNameEmail())
                .append("\n");
          }
        } else {
          throw new AuthException("delete not permitted");
        }
      }
      if (del.isEmpty()) {
        throw new ResourceNotFoundException();
      }
      ChangeUtil.bumpRowVersionNotLastUpdatedOn(rsrc.getChange().getId(), db);
      db.patchSetApprovals().delete(del);

      if (msg.length() > 0) {
        ChangeMessage changeMessage =
            new ChangeMessage(new ChangeMessage.Key(rsrc.getChange().getId(),
                ChangeUtil.messageUUID(db)),
                ((IdentifiedUser) control.getCurrentUser()).getAccountId(),
                TimeUtil.nowTs(), rsrc.getChange().currentPatchSetId());
        changeMessage.setMessage(msg.toString());
        db.changeMessages().insert(Collections.singleton(changeMessage));
      }

      db.commit();
    } finally {
      db.rollback();
    }
    indexer.index(db, rsrc.getChange());
    return Response.none();
  }

  private static String formatLabelValue(short value) {
    if (value > 0) {
      return "+" + value;
    } else {
      return Short.toString(value);
    }
  }

  private Iterable<PatchSetApproval> approvals(ReviewDb db,
      ReviewerResource rsrc) throws OrmException {
    final Account.Id user = rsrc.getUser().getAccountId();
    return Iterables.filter(
        db.patchSetApprovals().byChange(rsrc.getChange().getId()),
        new Predicate<PatchSetApproval>() {
          @Override
          public boolean apply(PatchSetApproval input) {
            return user.equals(input.getAccountId());
          }
        });
  }
}
