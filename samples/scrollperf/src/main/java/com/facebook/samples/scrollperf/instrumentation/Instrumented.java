/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.instrumentation;

import com.facebook.infer.annotation.Nullsafe;

/** Interface implemented by instrumented components */
@Nullsafe(Nullsafe.Mode.LOCAL)
public interface Instrumented {

  /**
   * Forget what happened so far and start measuring once again.
   *
   * @param tag String used to identify the image request
   * @param perfListener listener to be used to track the request state
   */
  void initInstrumentation(final String tag, PerfListener perfListener);
}
