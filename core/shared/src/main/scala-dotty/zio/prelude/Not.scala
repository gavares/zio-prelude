/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.prelude

import scala.annotation.implicitNotFound
import scala.util.NotGiven

/**
 * Provides implicit evidence that an instance of `A` is not in implicit scope.
 */
@implicitNotFound("Implicit ${A} defined.")
trait Not[A]

object Not {

  /**
   * Derives a `Not[A]` instance from a `NotGiven[A]` instance.
   */
  implicit def Not[A: NotGiven]: Not[A] =
    new Not[A] {}
}
