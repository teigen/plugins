import sbt._
import Keys._
import xml.{Elem, Text, Node => XmlNode}

object Idea extends Plugin {
  
  lazy val idea        = TaskKey[Unit]("idea")
  lazy val ideaModule  = TaskKey[Unit]("idea-module")
  lazy val ideaProject = TaskKey[Unit]("idea-project")
  lazy val ideaBase    = TaskKey[(String, ScalaInstance, java.io.File, java.io.File)]("idea-base")
  lazy val ideaCompile = TaskKey[(Seq[Attributed[File]], Seq[File], Seq[File], File)]("idea-compile")
  lazy val ideaTest    = TaskKey[(Seq[Attributed[File]], Seq[File], Seq[File], File)]("idea-test")
  
  override def settings = Seq(
    idea <<= ideas, 
    ideaModule <<= ideaModules, 
    ideaProject <<= ideaProjects,
    ideaBase <<= ideaBaseSettings,
    ideaCompile <<= ideaPaths(Compile),
    ideaTest <<= ideaPaths(Test))
    
  def ideaPaths(config:Configuration) = (
    dependencyClasspath   in config,
    sourceDirectories     in config,
    resourceDirectories   in config,
    classDirectory        in config
  ).map((a, b, c, d) => (a, b, c, d))
    
  def ideas = (ideaModule, ideaProject).map{ (a, b) => }
  
  def ideaBaseSettings = (name, scalaInstance, baseDirectory, target).map((a, b, c, d) => (a, b, c, d))
    
  def ideaModules = (ideaBase, ideaCompile, ideaTest).map{ 
      (ideaBase, compilePaths, testPaths) => 
    
    val (name, scalaInstance, baseDirectory, _target) = ideaBase
    // very quick and surprisingly dirty ;-)
    val base = baseDirectory.toString.length + 1    
    def relative(f:java.io.File) = f.getAbsolutePath.drop(base)
    
    val target        = relative(_target)
    
    val (_dependencies, _sources, _resources, _classDirectory) = compilePaths
    val dependencies  = _dependencies.map(_.data.getAbsolutePath)
    val sources       = _sources.map(relative)
    val resources     = _resources.map(relative)
    val classDirectory    = relative(_classDirectory)
    
    val (_testDependencies, _testSources, _testResources, _testClassDirectory) = testPaths
    val testDependencies  = _testDependencies.map(_.data.getAbsolutePath) filterNot dependencies.contains
    val testSources       = _testSources.map(relative)
    val testResources     = _testResources.map(relative)
    val testClassDirectory    = relative(_testClassDirectory)
    
    /* create directories */    
    val dotIdeaDir   = new File(baseDirectory, ".idea")
    dotIdeaDir.mkdirs
    val librariesDir = new File(dotIdeaDir, "libraries")
    librariesDir.mkdirs
    
    /* create files */    
    val buildScala = libraryTableComponent("scalaInstance", relative(scalaInstance.compilerJar), relative(scalaInstance.libraryJar))
    
    val modules = project(projectModuleManagerComponent(name))
    
    val descriptor = moduleDescriptor(      
      target,    
      dependencies,
      sources,
      resources,
      classDirectory,    
      testDependencies,
      testSources,
      testResources,
      testClassDirectory
    )
    
    val misc:xml.Node = miscTransformer(name).transform(miscXml(dotIdeaDir)).flatMap(identity).head
    
    /* save files */
    xml.XML.save(new File(dotIdeaDir, "modules.xml").getAbsolutePath, modules)
    xml.XML.save(new File(dotIdeaDir, "misc.xml").getAbsolutePath, misc)
    xml.XML.save(new File(baseDirectory, "%s.iml".format(name)).getAbsolutePath, descriptor)
    xml.XML.save(new File(librariesDir, "scalaInstance.xml").getAbsolutePath, buildScala)
  }
  
  def ideaProjects = target.map(_ => ())
  
  def project(inner: XmlNode*): XmlNode = <project version="4">{inner}</project>
  
  val defaultMiscXml = project(<component name="ProjectRootManager"/>, <component name="ProjectDetails"/>)

  private def miscXml(configDir: File) = try {
    xml.XML.loadFile(new File(configDir, "misc.xml"))
  } catch {
    case e: java.io.FileNotFoundException => defaultMiscXml
  }

  def miscTransformer(moduleName:String) = new xml.transform.RuleTransformer(
    new xml.transform.RewriteRule () {
      override def transform (n: XmlNode): Seq[XmlNode] = n match {
        case e @ Elem(_, "component", _, _, _*) if (e \ "@name").text == "ProjectDetails" => projectDetailsComponent(moduleName)
        case e @ Elem(_, "component", _, _, _*) if (e \ "@name").text == "ProjectRootManager" => projectRootManagerComponent
        case _ => n
      }
    }
  )
  
  private def projectRootManagerComponent: xml.Node =
    <component name="ProjectRootManager" version="2" languageLevel="JDK_1_6" assert-keyword="true" jdk-15="true" project-jdk-name="1.6" project-jdk-type="JavaSDK">
      <output url={String.format("file://$PROJECT_DIR$/%s", "out")} />
    </component>

  private def projectDetailsComponent(name:String): xml.Node =
    <component name="ProjectDetails">
      <option name="projectName" value={name} />
    </component>
  
  def projectModuleManagerComponent(name:String): xml.Node =
    <component name="ProjectModuleManager">
      <modules>
        <module fileurl={String.format("file://$PROJECT_DIR$%s/%s.iml", "", name)} filepath={String.format("$PROJECT_DIR$%s/%s.iml", "", name)} />
      </modules>
    </component>
  
  def libraryTableComponent(libraryName: String, scalaCompiler:String, scalaLibrary:String): xml.Node =
    <component name="libraryTable">
      <library name={libraryName}>
        <CLASSES>
          <root url={String.format("jar://$PROJECT_DIR$/%s!/", scalaCompiler)} />
          <root url={String.format("jar://$PROJECT_DIR$/%s!/", scalaLibrary)} />
        </CLASSES>
        <JAVADOC />
        <SOURCES/>
      </library>
    </component>    
  
  def moduleDescriptor(target:String, dependencies:Seq[String], sources:Seq[String], resources:Seq[String], classDirectory:String, testDependencies:Seq[String], testSources:Seq[String], testResources:Seq[String], testClassDirectory:String) =
    <module type="JAVA_MODULE" version="4">
      <component name="FacetManager">
        <facet type="scala" name="Scala">
          <configuration>
            <option name="compilerLibraryLevel" value="Project" />
            <option name="compilerLibraryName" value="scalaInstance" />
          </configuration>
        </facet>
      </component>
      <component name="NewModuleRootManager" inherit-compiler-output="false">
        <output url={"file://$MODULE_DIR$/" + classDirectory} />
        <output-test url={"file://$MODULE_DIR$/" + testClassDirectory} />
        <exclude-output/>
        <content url="file://$MODULE_DIR$">
          { sources.map(      path => <sourceFolder url={"file://$MODULE_DIR$/" + path} isTestSource="false" />) }
          { resources.map(    path => <sourceFolder url={"file://$MODULE_DIR$/" + path} isTestSource="false" />) }
          { testSources.map(  path => <sourceFolder url={"file://$MODULE_DIR$/" + path} isTestSource="true" />) }
          { testResources.map(path => <sourceFolder url={"file://$MODULE_DIR$/" + path} isTestSource="true" />) }
          <excludeFolder url={String.format("file://$MODULE_DIR$/%s", target)} />
        </content>
        <orderEntry type="inheritedJdk"/>
        <orderEntry type="sourceFolder" forTests="false"/>
        <orderEntry type="library" name="scalaInstance" level="project"/>
        { dependencies.map(url => 
        <orderEntry type="module-library" exported=" ">
          <library>
            <CLASSES>
              <root url={"jar://%s!/".format(url)}/>
            </CLASSES>
            <JAVADOC>              
            </JAVADOC>
            <SOURCES>
            </SOURCES>
          </library>
        </orderEntry>)}
        { testDependencies.map(url => 
        <orderEntry type="module-library" exported=" " scope="TEST">
          <library>
            <CLASSES>
              <root url={"jar://%s!/".format(url)}/>
            </CLASSES>
            <JAVADOC>              
            </JAVADOC>
            <SOURCES>
            </SOURCES>
          </library>
        </orderEntry>)}        
      </component>
    </module>
}