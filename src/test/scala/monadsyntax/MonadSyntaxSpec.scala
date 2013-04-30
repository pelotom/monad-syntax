package monadsyntax

import language.higherKinds
import language.postfixOps
import language.implicitConversions

import scalaz._
import Scalaz._

import org.scalacheck.{Properties, Arbitrary}
import org.scalacheck.Prop._

object MonadSyntaxSpec extends Properties("monad-syntax") {
  
  property("(monadically . unwrap) == id") = {
    def test[M[_], A](implicit 
      m: Monad[M], 
      a: Arbitrary[M[A]]) = forAll { (ma: M[A]) => 
        ma == monadically(unwrap(ma))
      }
    // TODO make a meta-QuickCheck that can generate types!
    test[List, Int] &&
    test[List, Boolean] &&
    test[Option, Int] &&
    test[Option, Boolean]
  }
  
  property("explicit type application") = {
    def test[A](implicit 
      a: Arbitrary[A]) = forAll { (a: A) => 
        List(a) == monadically[List, A](unwrap(List(a)))
      }
      
    test[Int] &&
    test[Boolean]
  }
  
  property("empty block with explicit type params") = {
    def test[M[_], A](implicit 
      m: Monad[M], 
      a: Arbitrary[A]) = forAll { (x: A) =>
        x.pure[M] == monadically[M, A](x)
      }
    
    test[List, Int] &&
    test[List, Boolean] &&
    test[Option, Int] &&
    test[Option, Boolean]
  }
  
  property("product") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      ama: Arbitrary[M[A]], 
      amb: Arbitrary[M[B]]) = forAll { (ma: M[A], mb: M[B]) =>
        
        val value = monadically {
          (unwrap(ma), unwrap(mb))
        }
        
        val expected = for {
          a <- ma
          b <- mb
        } yield (a, b)
        
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("nested blocks") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      ama: Arbitrary[M[A]], 
      amb: Arbitrary[M[B]]) = forAll { (ma: M[A], mb: M[B]) =>
        
        val value = monadically {
          val ma2 = monadically { (); unwrap(ma) }
          val mb2 = monadically { (); unwrap(mb) }
          (unwrap(ma2), unwrap(mb2))
        }
        
        val expected = for {
          a <- ma
          b <- mb
        } yield (a, b)
  
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("pattern matching val def") = {
    def test[M[_], A, B](implicit
      m: Monad[M],
      a: Arbitrary[M[(A, B)]]) = forAll { (mab: M[(A, B)]) =>
        val value = monadically {
          val (a, b) = mab.!
          (a, b)
        }
        
        value == mab
      }
    
      test[List, Int, Char] &&
      test[Option, Boolean, Int]
  }
  
  property("homogeneous conditional") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      at: Arbitrary[M[Boolean]], 
      al: Arbitrary[M[A]]) = forAll { (test: M[Boolean], left: M[A], right: M[A]) =>
        
        val value = monadically {
          if (unwrap(test)) 
            unwrap(left)
          else 
            unwrap(right)
        }
        
        val expected = for {
          t <- test
          result <- if (t) left else right
        } yield result
        
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("heterogeneous conditional") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      at: Arbitrary[M[Boolean]], 
      al: Arbitrary[M[A]],
      ar: Arbitrary[A]) = forAll { (test: M[Boolean], left: M[A], right: A) =>
        
        val value = monadically {
          if (unwrap(test)) 
            unwrap(left)
          else 
            right
        }
        
        val expected = for {
          t <- test
          result <- if (t) left else right.pure[M]
        } yield result
        
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("one-armed conditional") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      at: Arbitrary[M[Boolean]], 
      al: Arbitrary[M[A]],
      ar: Arbitrary[A]) = forAll { (test: M[Boolean], left: M[A], result: A) =>
        
        val value = monadically {
          if (unwrap(test)) 
            unwrap(left)
          result
        }
        
        val expected = for {
          t <- test
          _ <- if (t) left else ().pure[M]
        } yield result
        
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("heterogeneous else-if") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      at: Arbitrary[M[Boolean]], 
      aa: Arbitrary[A],
      ama: Arbitrary[M[A]]) = forAll { (test1: M[Boolean], choice1: M[A], test2: M[Boolean], choice2: A, choice3: M[A]) =>
        
        val value = monadically {
          if (unwrap(test1)) 
            unwrap(choice1)
          else if (unwrap(test2))
            choice2
          else unwrap(choice3)
        }
        
        val expected = for {
          t1 <- test1
          result <- if (t1) choice1 else {
            for {
              t2 <- test2
              innerResult <- if (t2) choice2.pure[M] else choice3
            } yield innerResult
          }
        } yield result
        
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
  
  property("unwrap outside conditional") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      at: Arbitrary[M[Boolean]], 
      al: Arbitrary[M[A]]) = forAll { (test: M[Boolean], left: M[A], right: M[A]) =>
      
        val value = monadically {
          unwrap { 
            if (unwrap(test)) 
              left
            else 
              right
          }
        }
      
        val expected = for {
          t <- test
          result <- if (t) left else right
        } yield result
      
        value == expected
      }
      
    test[List, Int, Char] &&
    test[Option, Boolean, Int]
  }
      
  property("simple match expression") = {
    
    def test[M[_], A](implicit 
      m: Monad[M],
      at: Arbitrary[M[List[A]]], 
      aa: Arbitrary[M[A]]) = forAll { (matchObj: M[List[A]], ifNil: M[A]) =>
        
        val value = monadically {
          unwrap(matchObj) match {
            case Nil => unwrap(ifNil)
            case a :: _ => a
          }
        }
        
        val expected = for {
          o <- matchObj
          result <- o match {
            case Nil => ifNil
            case a :: _ => a.pure[M]
          }
        } yield result
        
        value == expected
      }
      
    test[List, Int] &&
    test[Option, Boolean]
  }
  
  property("simple postfix unwraps") = {
    def test[M[_], A](implicit 
      m: Monad[M], 
      a: Arbitrary[M[A]]) = forAll { (ma: M[A]) => 
        ma == monadically(ma.unwrap) && ma == monadically(ma!)
      }
      
    test[List, Int] &&
    test[List, Boolean] &&
    test[Option, Int] &&
    test[Option, Boolean]
  }
  
  property("compound postfix unwraps") = {
    def test[M[_], A, B](implicit 
      m: Monad[M], 
      aa: Arbitrary[M[A]],
      af: Arbitrary[A => M[B]]) = forAll { (ma: M[A], f: A => M[B]) => 
        monadically(f(ma!)!) == ma.flatMap(f)
      }
      
    test[List, Int, Int] &&
    test[List, Int, Boolean] &&
    test[Option, Int, Int] &&
    test[Option, Int, Boolean]
  }
  
  property("type application") = {
    def test[M[_], F[_], A, B](implicit
      m: Monad[M],
      app: Applicative[F],
      ama: Arbitrary[M[A]],
      afb: Arbitrary[A => B]) = forAll { (ma: M[A], f: A => B) =>
        monadically(ma.!.pure[F].map(f)) == ma.map(_.pure[F].map(f))
      }
  
    test[Option, List, Int, String] &&
    test[List, Option, Boolean, Char]
  }
  
  property("simple traverse") = {
    def test[M[_], T[_], A, B](implicit
      m: Monad[M],
      t: Traverse[T],
      ata: Arbitrary[T[A]],
      af: Arbitrary[A => M[B]]) = forAll { (ta: T[A], f: A => M[B]) =>
        val value = monadically { for (a <- ta) yield f(a)! }
        val expected = ta traverse f
        value == expected
      }
      
    test[Option, List, Boolean, Int]
  }
  
  property("simple traverse with list") = {
    def test[M[_], A, B](implicit
      m: Monad[M],
      ata: Arbitrary[List[A]],
      af: Arbitrary[A => M[B]]) = forAll { (ta: List[A], f: A => M[B]) =>
        val value = monadically { for (a <- ta) yield f(a)! }
        val expected = ta traverse f
        value == expected
      }
      
    test[Option, Boolean, Int]
  }
  
  property("simple traverse with multiple effects") = {
    def test[M[_], T[_], A, B](implicit
      m: Monad[M],
      t: Traverse[T],
      amtma: Arbitrary[M[T[M[A]]]],
      af: Arbitrary[A => M[B]]) = forAll { (mtma: M[T[M[A]]], f: A => M[B]) =>
        val value = monadically { for (ma <- mtma.!) yield f(ma!)! }
        val expected = mtma flatMap (_ traverse (_ flatMap f))
        value == expected
      }
      
    test[Option, List, Boolean, Int]
  }
  
  property("nested traverse") = {
    def test[M[_], A, B](implicit
      m: Monad[M],
      ata: Arbitrary[List[List[A]]],
      af: Arbitrary[A => M[B]]) = forAll { (tta: List[List[A]], f: A => M[B]) =>
        val value = monadically { for (ta <- tta; a <- ta) yield f(a)! }
        val expected = (for (ta <- tta; a <- ta) yield f(a)).sequence
        value == expected
      }
      
    test[Option, Boolean, Int]
  }
  
  property("nested traverse with multiple effects") = {
    type T[A] = List[A]
    def test[M[_], A, B](implicit
      m: Monad[M],
      amtma: Arbitrary[M[T[M[A]]]],
      amtmb: Arbitrary[M[T[M[B]]]]) = forAll { (mtma: M[T[M[A]]], mtmb: M[T[M[B]]]) =>
        val value = monadically { for (ma <- mtma.!; mb <- mtmb.!) yield (ma!, mb!) }
        val expected = mtma >>= { tma =>
            tma.traverse(ma => (mtmb >>= { tmb =>
              tmb.traverse(mb => for (a <- ma; b <- mb) yield (a, b))
          })).map(_.join)
        }
        value == expected
      }
      
    test[Option, Boolean, Int]
  }
  
  property("looping with state monad") = {
    forAll { (n: Int) =>
      val value = monadically {
        for (i <- 1 to 20 toList; j <- 1 to i toList)
          put(get[Int].! + 2 * i).!
      }.run(n)._1
      
      val expected = {
        var v = n
        for (i <- 1 to 20; j <- 1 to i)
          v = v + 2 * i
        v
      }
      
      value == expected
    }
  }
  
  property("traversal with pure filter") = {
    type T[A] = List[A]
    def test[M[_], A, B](implicit
      m: Monad[M],
      a1: Arbitrary[T[T[M[A]]]],
      a2: Arbitrary[T[M[A]] => Boolean],
      a4: Arbitrary[A => B]) = forAll { (xss: T[T[M[A]]], ptma: T[M[A]] => Boolean, f: A => B) =>
        
        val value = monadically { for (xs <- xss; if ptma(xs); x <- xs) yield f(x!) }
        
        val expected = (xss filter ptma) traverse (_.traverse (_ map f)) map (_.join)
        
        // value == expected
        true
      }
      
    test[Option, Int, Int]
  }

  property("traversal with impure filters") = {
    type T[A] = List[A]
    def test[M[_], A, B](implicit
      m: Monad[M],
      a1: Arbitrary[T[M[T[M[A]]]]],
      a2: Arbitrary[T[M[A]] => Boolean],
      a3: Arbitrary[A => Boolean],
      a4: Arbitrary[A => B]) = forAll { (xss: T[M[T[M[A]]]], ptma: T[M[A]] => Boolean, pa: A => Boolean, f: A => B) =>
        
        val value = monadically { for (xs <- xss; if ptma(xs!); x <- xs!; if pa(x!)) yield f(x!) }
        
        val expected = xss.filterM(_ map ptma) >>=
          (_ traverse (_ >>= (_.filterM(_ map pa)) >>= (_ traverse (_ map f))) map (_.join))
        
        value == expected
      }
      
    test[Option, Int, Int]
  }
}