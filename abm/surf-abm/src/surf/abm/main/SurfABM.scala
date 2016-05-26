package surf.abm.main

import java.io.File
import java.lang.reflect.{Constructor, Method}

import _root_.surf.abm.agents.Agent
import com.typesafe.config.ConfigException
import com.vividsolutions.jts.geom.{Envelope, GeometryFactory}
import com.vividsolutions.jts.planargraph.Node
import org.apache.log4j.Logger
import sim.engine.SimState
import sim.field.geo.GeomVectorField
import sim.io.geo.ShapeFileImporter
import sim.util.Bag
import sim.util.geo.{GeomPlanarGraph, MasonGeometry}
import surf.abm.environment.{Building, Junction}
import surf.abm.surfutil.Util

import scala.collection.JavaConversions._


/**
  * Main class for the surf agent-based model. This doesn't actually do very much,
  * almost all of the logic is in the companion singleton object.
  *
  * Use this class to run the model in headless model (without a GUI). E.g. to run
  * for 5000 iterations, outputting the time each 1000 steps use:
  * <pre><code>
  *   scala surf.abm.main.SurfABM -for 5000 -time 1000
  * </pre></code>
  * To get more help options, do:
  * <pre><code>
  *   scala surf.abm.main.SurfABM -help
  * </pre></code>
  * Note that you need to set up the classpath for the above commands to work. See
  * the file <code>run.sh</code> for an example.
  *
  */
@SerialVersionUID(1L)
class SurfABM(seed: Long) extends SimState(seed) {

  /**
    * Start the simulation. This is called after the SurfABM object has been initialised, which prepares the
    * environment etc. The main thing that this function does is initialise the clock and decide how to load agents.
    */
  override def start(): Unit = {
    super.start

    // Initialise the clock. Currently using a default time period, but this could just as easily be defined
    // by configuration parameters.
    Clock.create(this)

    // Create the outputter that is in charge of writing out results etc.
    OutputFactory(this)

    // Decide how to load agents. Configurations can set their own loader, or just use the default (NumAgents of type
    // AgentType are created at random buildings
    try {
      val loader = SurfABM.conf.getString(SurfABM.ModelConfig+".AgentLoader")
      // A loader has been specfied, work work out which function to call using Java reflection
      // The loader definition should be of the form "Class::Method"
      val split =  loader.split("::")
      if (split.size != 2) {
        throw new Exception(s"Invalid agent loader definition: $loader . The loader should be in the format Class.Method")
      }
      val classStr = split(0)
      val methodStr = split(1)
      SurfABM.LOG.info(s"Will attempt to load agents using method '${methodStr}' in class '{$classStr}'.")

      // Call the method using Java reflection. (I looked at Scala reflection but it was absoultely incomprehensible!)
      // https://stackoverflow.com/questions/160970/how-do-i-invoke-a-java-method-when-given-the-method-name-as-a-string
      val cls : Class[_] = Class.forName(classStr)
      val method : Method = cls.getMethod(methodStr, this.getClass) // Note: the method should receive one parameter: the model state
      method.invoke(cls, this) // Invoke the method on the class, passing this (the model state) as the only parameter

    }
    catch {
      case _ : ConfigException.Missing => { // If no loader has been specified
        SurfABM.LOG.info("No agent loader defined, use default")
        // Number of agents
        val numAgents: Int = SurfABM.conf.getInt(SurfABM.ModelConfig+".NumAgents");
        val agentClassName: String = SurfABM.conf.getString(SurfABM.ModelConfig+".AgentType")
        SurfABM.LOG.info(s"Will create $numAgents agents of type $agentClassName using the default method.");
        SurfABM.createDefaultAgents(this, numAgents, agentClassName)
      }
    }

  }

  override def finish(): Unit = {
    super.finish()
    // Tell the outputter to finish (e.g. close output files).
    OutputFactory(this).finish()
  }

} // class surfABM


@SerialVersionUID(1L)
object SurfABM extends Serializable {

  // Initialise the logger
  private val LOG: Logger = Logger.getLogger(this.getClass);

  // ****** Initialise the model ******

  // Get the configuration reader.
  val conf = Util.config()
  // println("DATA DIR:",conf.getString("DataDir"))

  // Find out which model configuration to use
  val ModelConfig = conf.getString("ModelConfig")

  // Not sure why these are necessary. Probably just for initialisation
  val WIDTH = conf.getInt("WIDTH");
  val HEIGHT = conf.getInt("HEIGHT");


  // A list of all the agent geometries
  val agentGeoms = new GeomVectorField(WIDTH, HEIGHT);

  // Keep a map of agents and their geometries. This is created after the agents have been created
  ///var agentGeomMap : Map[SurfGeometry,Agent] = null

  // Spatial layers. One function to read them all
  val (buildingGeoms, buildingIDGeomMap, roadGeoms, network, junctions, mbr) = _readEnvironmentData()

    /**
      * Read and configure the buildings, roads, networks and junctions.
      * This is written as a function so that it can be tested elsewhere.
      *
      * @return
      */
    private def _readEnvironmentData()
    //: (GeomVectorField, Map[Int, SurfGeometry], GeomVectorField, GeomPlanarGraph, GeomVectorField, Envelope)
    = {

      try {
        /* Read the GIS files into the relevant fields */

        // Maintain a maximum bounding envelope for all layers
        var MBR: Envelope = null // Minimum envelope surrounding whole world

        // Directory where the data are stored
        val dataDir = SurfABM.conf.getString(ModelConfig+".DataDir")
        SurfABM.LOG.info(s"Reading GIS data for the environment from ${dataDir}")

        // Start with buildings
        val tempBuildings = new GeomVectorField(WIDTH, HEIGHT);
        val buildings = new GeomVectorField(WIDTH, HEIGHT);
        // Declare the fields from the shapefile that should be read in with the geometries
        // GeoMason wants these to be a Bag
        val attributes: Bag = new Bag( (for (v <- BUILDING_FIELDS.values) yield v.toString()) ) // Add all of the fields
        // Read the shapefile (path relative from 'surf' directory)
        val bldgURI = new File("data/" + dataDir + "/buildings.shp").toURI().toURL();
        LOG.debug("Reading buildings  from file: " + bldgURI + " ... ");
        ShapeFileImporter.read(bldgURI, tempBuildings, attributes);
        LOG.debug("...read %d buildings".format(tempBuildings.getGeometries.size))

        // Now cast all buildings from MasonGeometrys to SurfGeometrys
        LOG.debug("Casting buildings to SurfGeometry objects")
        //val sgoms = scala.collection.mutable.ListBuffer.empty[SurfGeometry[Building]]
        for (o <- tempBuildings.getGeometries()) {
          val g : MasonGeometry = o.asInstanceOf[MasonGeometry]
          val buildingID = try {
            g.getIntegerAttribute(BUILDING_FIELDS.BUILDINGS_ID.toString())
          }
          catch { case e: NullPointerException => {
              LOG.error("Cannot find a field called '%s' in the buldings file. Does it have a colunm called '%s'?".
                format(BUILDING_FIELDS.BUILDINGS_ID.toString(),BUILDING_FIELDS.BUILDINGS_ID.toString()), e)
              throw e
            }
          }
          val building = Building(buildingID)
          val s = SurfGeometry(g,building)
          buildings.addGeometry(s)
        }
        //buildings.updateSpatialIndex()
        //println("Gometryies", buildings.getGeometries.size())

        LOG.debug("Creating id -> buildings map")
        // Keep a link between the building IDs and their geometries (ID -> geometry). This makes for quick building lookups
        // Use a for comprehension to create a temp array of (Int, SurfGeometry) then use that as input to a map
        // Note, to go 'backwards' (i.e. from a location to an ID) do: origMap.map(_.swap)
        // (https://stackoverflow.com/questions/2338282/elegant-way-to-invert-a-map-in-scala)

       // val tempArray: collection.immutable.Seq[(Int, SurfGeometry)] =

        val b_ids: Map[Int, SurfGeometry[Building]] = scala.collection.immutable.Map[Int, SurfGeometry[Building]](
          {
            for (o <- buildings.getGeometries())
              yield {
                val g = o.asInstanceOf[SurfGeometry[Building]]
                Int.unbox(g.getIntegerAttribute(BUILDING_FIELDS.BUILDINGS_ID.toString)) -> g
              }
          }.to[collection.immutable.Seq]: _*) // Splat the array with :_*

        assert(buildings.getGeometries.size() == b_ids.size)
        SurfABM.LOG.debug(s"\t ... finished creating map for ${b_ids.size} buildings")


        // We want to save the MBR so that we can ensure that all GeomFields
        // cover identical area.
        MBR = buildings.getMBR() // Minimum envelope surrounding whole world


        // Read roads
        val roads = new GeomVectorField(WIDTH, HEIGHT)
        val roadsURI = new File("data/" + dataDir + "/roads.shp").toURI().toURL()
        LOG.debug(s"Reading roads file: ${roadsURI} ...")
        ShapeFileImporter.read(roadsURI, roads);
        LOG.debug(s"\t... read ${roads.getGeometries().size()} roads")
        MBR.expandToInclude(roads.getMBR())
        LOG.debug("Finished reading roads and buildings data.");


        // Now synchronize the MBR for all GeomFields to ensure they cover the same area
        buildings.setMBR(MBR);
        roads.setMBR(MBR);

        // Stores the network connections.  We represent the walkways as a PlanarGraph, which allows
        // easy selection of new waypoints for the agents.
        val network = new GeomPlanarGraph()
        val junctions = new GeomVectorField(WIDTH, HEIGHT) // nodes for intersections

        SurfABM.LOG.debug("Creating road network")
        network.createFromGeomField(roads)
        val fact = new GeometryFactory();
        // Now add the associated junctions to the junctions geometry.
        network.getNodes().foreach( x => {
          val node = x match { // cast the object x to a Node
            case y: Node => y
            case _ => throw new ClassCastException
          }
          junctions.addGeometry(
            SurfGeometry[Junction](
              new MasonGeometry(fact.createPoint(node.getCoordinate())),
              Junction(node)
            )
          )
        } ) // foreach


        SurfABM.LOG.info("Finished creating network and junctions")


        LOG.info("Finished initialising model environment")

        // Return the layers
        (buildings, b_ids, roads, network, junctions, MBR)
      }
      catch {
        case e: Exception => {
          SurfABM.LOG.error("Error while reading GIS data: %s".format(e.toString), e)
          throw e
        }
      }

   } // _readEnvironmentData()

  /**
    * Create agents. This needs to be called *after* the model has finished initialising.
    * This is the default way to create them, using the agent type (AgentType) and number
    * of agents (NumAgents) set in the configuration file. The agents are randomly assigned
    * to buildings.
    *
    * @param state
    * @return
    */
  private def createDefaultAgents(state : SurfABM, numAgents: Int, agentClassName: String) = {
    SurfABM.agentGeoms.clear
    try {
      // Find the class to use to create agents.
      val cls: Class[Agent] = Class.forName(agentClassName).asInstanceOf[Class[Agent]]
      val c: Constructor[Agent] = cls.getConstructor(classOf[SurfABM], classOf[SurfGeometry[Agent]])

      SurfABM.LOG.info(s"Creating ${numAgents} agents of type ${cls.toString}")

      // Keep a list of the Agents and their Geometries. This will be turned into a Map shortly,
      //val agentArray = collection.mutable.ListBuffer.empty[(SurfGeometry[Agent],Agent)]

      // Create the agents
      for (i <- 0.until(numAgents)) {
        // Create a new a agent, passing the main model instance and a random new location
        val a: Agent = c.newInstance(state, SurfABM.getRandomBuilding(state))
        SurfABM.agentGeoms.addGeometry(SurfGeometry[Agent](a.location, a))
        //SurfABM.agentGeoms.addGeometry(new MasonGeometry(a.location().getGeometry()))
        state.schedule.scheduleRepeating(a)
        //agentArray += ( (a.location, a) ) // Need two  parantheses to make a tuple?
      }

      // Now store the agents and their geometries in a map so we can get back to the
      // agents from their geometry. This is necessary because most of the GeoMason containers
      // only store the geometry, not the underlying agent.
      //SurfABM.agentGeomMap = Map[SurfGeometry,Agent](agentArray: _*)

      assert(
        numAgents == SurfABM.agentGeoms.getGeometries().size()
        //SurfABM.numAgents == agentArray.size &&
        //SurfABM.numAgents == SurfABM.agentGeomMap.size,
        , s"Lengths of agent arrays differ. \n\t" +
          s"numAgents: ${numAgents}\n\t" +
          s"agentGeoms: ${SurfABM.agentGeoms}\n\t"
          //s"agentGeomMap: ${SurfABM.agentGeomMap.size}"
      )

      SurfABM.agentGeoms.setMBR(SurfABM.mbr)

      // Ensure that the spatial index is made aware of the new agent
      // positions.  Scheduled to guaranteed to run after all agents moved.
      state.schedule.scheduleRepeating(SurfABM.agentGeoms.scheduleSpatialIndexUpdater, Integer.MAX_VALUE, 1.0)

    }
    catch {
      case e: Exception => {
        SurfABM.LOG.error("Exception while creating agents, cannot continue", e)
        throw e
      }
    }

  }



  /**
    * Find a building, chosen at random.
    *
    * @param state An instance of the SimState that is running the model
    * @return
    */
  def getRandomBuilding(state: SimState): SurfGeometry[Building] = {
    // Get a random building
    val o = SurfABM.buildingGeoms.getGeometries.get(state.random.nextInt(SurfABM.buildingGeoms.getGeometries().size()))
    // cast it to a MasonGemoetry using pattern matching (throwing an error if not possible)
    o match {
      case x: SurfGeometry[Building @unchecked] => x
      case _ => throw new ClassCastException
    }
  }


  def apply(l:Long) = new SurfABM(l)

  /* Main application entry point */
  def main(args: Array[String]): Unit = {

    try {
      LOG.debug("Beginning do loop")
      SimState.doLoop(classOf[SurfABM], args);
      LOG.debug("Finished do loop")
    }
    catch {
      case e: Exception => {
        this.LOG.error("Exception thrown in main loop", e)
        throw e
      }
    }
  }

} // surfabm object