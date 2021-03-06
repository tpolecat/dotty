object templateParents {

  // traits do not call a constructor
  class C[+T](x: T)
  trait D extends C[String]
  trait E extends C[Int]
  new C("abc") with D

}

object templateParents1 {
  // tests inference of synthesized class type
  class C[+T]
  trait D extends C[String]
  trait E extends C[Int]

  val x = new D with E

  val y: C[Int & String] = x
}

