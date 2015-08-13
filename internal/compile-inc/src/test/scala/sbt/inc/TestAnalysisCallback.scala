package sbt.inc

import java.io.File
import scala.collection.mutable.ArrayBuffer
import xsbti.api.{ Compilation, Source, SourceAPI }
import xsbt.api.{ HashAPI, SameAPI }
import xsbti._
import xsbti.DependencyContext._
import sbt.Relation

case class TestAnalysis(
  relations: sbt.inc.Relations,
  sourceDependencies: Set[(File, File, DependencyContext)],
  binaryDependencies: Set[(File, String, File, DependencyContext)],
  products: Set[(File, File, String)],
  usedNames: Map[File, Set[String]],
  apis: APIs) {

  def merge(o: TestAnalysis, deletedFiles: Seq[File]): TestAnalysis = {
    TestAnalysis(
      relations ++ o.relations -- deletedFiles,
      sourceDependencies ++ o.sourceDependencies filterNot (f => deletedFiles contains f._2),
      binaryDependencies ++ o.binaryDependencies filterNot (f => deletedFiles contains f._3),
      products ++ o.products filterNot (f => deletedFiles contains f._1),
      usedNames ++ o.usedNames filterKeys (k => !(deletedFiles contains k)),
      apis ++ o.apis removeInternal deletedFiles)
  }
}
object TestAnalysis {
  val Empty = TestAnalysis(Relations.empty, Set.empty, Set.empty, Set.empty, Map.empty, APIs.empty)
}

class TestAnalysisCallback(override val nameHashing: Boolean = false) extends xsbti.AnalysisCallback {
  val sourceDependencies = new ArrayBuffer[(File, File, DependencyContext)]
  val binaryDependencies = new ArrayBuffer[(File, String, File, DependencyContext)]
  val products = new ArrayBuffer[(File, File, String)]
  val usedNames = scala.collection.mutable.Map.empty[File, Set[String]].withDefaultValue(Set.empty)
  val apis: scala.collection.mutable.Map[File, Source] = scala.collection.mutable.Map.empty

  def hashFile(f: File): Array[Byte] = Stamp.hash(f).asInstanceOf[Hash].value

  def get: TestAnalysis = {

    val p = (products foldLeft Relation.empty[File, File]) {
      case (rel, (source, module, _)) => rel + (source -> module)
    }

    val bin = (binaryDependencies foldLeft Relation.empty[File, File]) {
      case (rel, (binary, _, source, _)) => rel + (source -> binary)
    }

    val di = Relation.empty[File, File]
    val de = Relation.empty[File, String]

    val pii = Relation.empty[File, File]
    val pie = Relation.empty[File, String]

    val mri = (sourceDependencies.filter(_._3 == DependencyByMemberRef) foldLeft Relation.empty[File, File]) {
      case (rel, (dependsOn, source, _)) => rel + (source -> dependsOn)
    }
    val mre = Relation.empty[File, String]

    val ii = (sourceDependencies.filter(_._3 == DependencyByInheritance) foldLeft Relation.empty[File, File]) {
      case (rel, (dependsOn, source, _)) => rel + (source -> dependsOn)
    }
    val ie = Relation.empty[File, String]

    val cn = Relation.empty[File, String]

    val un = (usedNames foldLeft Relation.empty[File, String]) {
      case (rel, (source, names)) => rel ++ (names map (n => (source, n)))
    }

    val relations = Relations.construct(true, p :: bin :: di :: de :: pii :: pie :: mri :: mre :: ii :: ie :: cn :: un :: Nil)

    TestAnalysis(relations, sourceDependencies.toSet, binaryDependencies.toSet, products.toSet, usedNames.toMap, APIs(apis.toMap, Map.empty))
  }

  def sourceDependency(dependsOn: File, source: File, inherited: Boolean): Unit = {
    val context = if (inherited) DependencyByInheritance else DependencyByMemberRef
    sourceDependency(dependsOn, source, context)
  }
  def sourceDependency(dependsOn: File, source: File, context: DependencyContext): Unit = { sourceDependencies += ((dependsOn, source, context)); () }
  def binaryDependency(binary: File, name: String, source: File, inherited: Boolean): Unit = {
    val context = if (inherited) DependencyByInheritance else DependencyByMemberRef
    binaryDependency(binary, name, source, context)
  }
  def binaryDependency(binary: File, name: String, source: File, context: DependencyContext): Unit = { binaryDependencies += ((binary, name, source, context)); () }
  def generatedClass(source: File, module: File, name: String): Unit = { products += ((source, module, name)); () }

  def usedName(source: File, name: String): Unit = { usedNames(source) += name }
  def api(source: File, sourceAPI: SourceAPI): Unit = {
    assert(!apis.contains(source), s"The `api` method should be called once per source file: $source")
    val nameHashes = new xsbt.api.NameHashing().nameHashes(sourceAPI)
    val sourceHash = hashFile(source)
    val src = new Source(new Compilation(0L, Array.empty), sourceHash, sourceAPI, sourceAPI.hashCode, nameHashes, false)
    apis(source) = src
  }
  def problem(category: String, pos: xsbti.Position, message: String, severity: xsbti.Severity, reported: Boolean): Unit = ()
}
