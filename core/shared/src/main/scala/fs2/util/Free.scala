package fs2.util

import fs2.internal.Trampoline

sealed trait Free[+F[_],+A] {
  import Free._
  def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)
  def map[B](f: A => B): Free[F,B] = Bind(this, f andThen (Free.Pure(_)))

  def fold[F2[_],G[_],A2>:A](suspend: (=> G[A2]) => G[A2], done: A => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
    implicit S: Sub1[F,F2], T: RealSupertype[A,A2])
    : G[A2]
    = this.step._fold(suspend, done, fail, bound)

  def translate[G[_]](u: F ~> G): Free[G,A] = {
    type FG[x] = Free[G,x]
    fold[F,FG,A](Free.suspend, Free.pure, Free.fail, new B[F,FG,A] { def f[x] = r =>
      r.fold({ case (fr,g) => Free.attemptEval(u(fr)) flatMap g },
             { case (r,g) => g(r) })
    })
  }

  def attempt: Free[F,Attempt[A]] = attempt_(true)
  def attemptStrict: Free[F,Attempt[A]] = attempt_(false)

  private
  def attempt_(trampoline: Boolean): Free[F,Attempt[A]] = {
    type G[x] = Free[F,Attempt[x]]
    fold[F,G,A](if (trampoline) Free.suspend else x => x, a => Free.pure(Right(a)), e => Free.pure(Left(e)),
      new B[F,G,A] { def f[x] = r =>
        r.fold({ case (fr,g) => Free.attemptEval(fr) flatMap g },
               { case (r,g) => try g(r) catch { case NonFatal(t) => Free.pure(Left(t)) } })
      }
    )
  }

  protected def _fold[F2[_],G[_],A2>:A](suspend: (=> G[A2]) => G[A2], done: A => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
    implicit S: Sub1[F,F2], T: RealSupertype[A,A2]): G[A2]

  def runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
    step._runTranslate(g)

  protected def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2]

  def unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
  : Unroll[A, G[Free[F,A]]]
  = this.step._unroll.run

  protected def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
  : Trampoline[Unroll[A, G[Free[F,A]]]]

  def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
    (this: Free[F2,A2]).runTranslate(UF1.id)

  @annotation.tailrec
  private[fs2] final def step: Free[F,A] = this match {
    case Bind(Bind(x, f), g) => (x flatMap (a => f(a) flatMap g)).step
    case _ => this
  }

  override def toString = "Free"
}

object Free {

  trait B[F[_],G[_],A] {
    def f[x]: Either[(F[x], Attempt[x] => G[A]), (x, x => G[A])] => G[A]
  }

  def attemptEval[F[_],A](a: F[A]): Free[F,Attempt[A]] = Eval(a)
  def fail(err: Throwable): Free[Nothing,Nothing] = Fail(err)
  def pure[A](a: A): Free[Nothing,A] = Pure(a)
  def attemptPure[A](a: => A): Free[Nothing,A] =
    try pure(a)
    catch { case NonFatal(e) => Fail(e) }
  def eval[F[_],A](a: F[A]): Free[F,A] = Eval(a) flatMap {
    case Left(e) => fail(e)
    case Right(a) => pure(a)
  }
  def suspend[F[_],A](fa: => Free[F,A]): Free[F,A] =
    pure(()) flatMap { _ => fa }

  private[fs2] case class Fail(err: Throwable) extends Free[Nothing,Nothing] {
    def _runTranslate[G[_],A2>:Nothing](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.fail(err)
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G])
    : Trampoline[Unroll[Nothing, G[Free[Nothing,Nothing]]]]
    = Trampoline.done { Unroll.Fail(err) }
    def _fold[F2[_],G[_],A2>:Nothing](suspend: (=> G[A2]) => G[A2], done: Nothing => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
      implicit S: Sub1[Nothing,F2], T: RealSupertype[Nothing,A2]): G[A2] = fail(err)
  }
  private[fs2] case class Pure[A](a: A) extends Free[Nothing,A] {
    def _runTranslate[G[_],A2>:A](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.pure(a)
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[Nothing,G])
    : Trampoline[Unroll[A, G[Free[Nothing,A]]]]
    = Trampoline.done { Unroll.Pure(a) }
    def _fold[F2[_],G[_],A2>:A](suspend: (=> G[A2]) => G[A2], done: A => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
      implicit S: Sub1[Nothing,F2], T: RealSupertype[A,A2])
    : G[A2] = done(a)
  }
  private[fs2] case class Eval[F[_],A](fa: F[A]) extends Free[F,Attempt[A]] {
    def _runTranslate[G[_],A2>:Attempt[A]](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.attempt { g(fa) }.asInstanceOf[G[A2]]

    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
    : Trampoline[Unroll[Attempt[A], G[Free[F,Attempt[A]]]]]
    = Trampoline.done { Unroll.Eval(G.map(S(fa))(a => Free.pure(Right(a)))) }

    def _fold[F2[_],G[_],A2>:Attempt[A]](
      suspend: (=> G[A2]) => G[A2],
      done: Attempt[A] => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
      implicit S: Sub1[F,F2], T: RealSupertype[Attempt[A],A2])
    : G[A2] = bound.f(Left((S(fa), (a: Attempt[A]) => done(a))))
  }
  private[fs2] case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A] {
    def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.flatMap(r._runTranslate(g))(f andThen (_.runTranslate(g)))
    def _unroll[G[+_]](implicit G: Functor[G], S: Sub1[F,G])
    : Trampoline[Unroll[A, G[Free[F,A]]]]
    = Sub1.substFree(r) match {
      case Pure(r) =>
        try Trampoline.suspend { f(r).step._unroll }
        catch { case NonFatal(err) => Trampoline.done { Unroll.Fail(err) } }
      case Fail(err) => Trampoline.done { Unroll.Fail(err) }
      case eval =>
        // NB: not bothering to convince Scala this is legit but since
        // `.step` returns a right-associated flatMap, and `Eval[F,A]` has type
        // Free[Attempt[A]], this is safe
        val ga: G[Any] = eval.asInstanceOf[Eval[G,Any]].fa
        val fr: Attempt[Any] => Free[F,A]
           = f.asInstanceOf[Attempt[Any] => Free[F,A]]
        Trampoline.done { Unroll.Eval(G.map(ga) { any => fr(Right(any)) }) }
    }
    def _fold[F2[_],G[_],A2>:A](
      suspend: (=> G[A2]) => G[A2],
      done: A => G[A2], fail: Throwable => G[A2], bound: B[F2,G,A2])(
      implicit S: Sub1[F,F2], T: RealSupertype[A,A2])
    : G[A2] = suspend { Sub1.substFree(r) match {
      case Pure(r) => bound.f[R](Right((r, f andThen (_.fold(suspend, done, fail, bound)))))
      case Fail(err) => fail(err)
      // NB: Scala won't let us pattern match on Eval here, but this is safe since `.step`
      // removes any left-associated flatMaps
      case eval => bound.f[R](Left(
        eval.asInstanceOf[Eval[F2,R]].fa ->
          f.asInstanceOf[Any => Free[F,A]].andThen(_.fold(suspend, done, fail, bound))))
    }}
  }

  sealed trait Unroll[+A,+B]
  object Unroll {
    case class Fail(err: Throwable) extends Unroll[Nothing,Nothing]
    case class Pure[A](a: A) extends Unroll[A,Nothing]
    case class Eval[B](e: B) extends Unroll[Nothing,B]
  }

  implicit def monad[F[_]]: Monad[({ type f[x] = Free[F,x]})#f] =
  new Monad[({ type f[x] = Free[F,x]})#f] {
    def pure[A](a: A) = Pure(a)
    def flatMap[A,B](a: Free[F,A])(f: A => Free[F,B]) = a flatMap f
  }
}
