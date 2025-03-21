/*
 * Copyright 2022 John A. De Goes and the ZIO Contributors
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

package zio.internal

import zio._

/**
 * Fiber messages are low-level messages processed by the fiber runtime. They
 * are processed in two modes: either while the fiber is suspended, in which
 * case one message (FiberMessage.Resume) can wake the fiber up, or while the
 * fiber is running.
 */
private[zio] sealed trait FiberMessage
private[zio] object FiberMessage {
  final case class InterruptSignal(cause: Cause[Nothing])                        extends FiberMessage
  final case class GenStackTrace(onTrace: StackTrace => Unit)                    extends FiberMessage
  final case class Stateful(onFiber: (FiberRuntime[_, _], Fiber.Status) => Unit) extends FiberMessage
  case object Resume                                                             extends FiberMessage
  case object YieldNow                                                           extends FiberMessage
}
