package igrek.robopath.simulation.whca;

import com.google.common.base.Joiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import igrek.robopath.common.Point;
import igrek.robopath.common.TileMap;
import igrek.robopath.mazegenerator.MazeGenerator;
import igrek.robopath.mazegenerator.NoNextFieldException;
import igrek.robopath.pathfinder.whca.Path;
import igrek.robopath.pathfinder.whca.ReservationTable;
import igrek.robopath.pathfinder.whca.WHCAPathFinder;
import javafx.util.Pair;

public class WHCAController {
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	private Random random;
	private MazeGenerator mazegen;
	
	private TileMap map;
	private Comparator<MobileRobot> robotsPriorityComparator = (o1, o2) -> {
		// reversed order
		return Integer.compare(o2.getPriority(), o1.getPriority());
	};
	private List<MobileRobot> robots = new ArrayList<>();
	private List<MobileRobot> robotsReached = new ArrayList<>();
	
	private WHCASimulationParams params;
	private boolean reorderNeeded = false;
	private volatile boolean calculatingPaths = false;
	private boolean prioritiesPromotion = true;
	private boolean timeWindowScaling = true;
	
	public WHCAController(WHCAPresenter presenter, WHCASimulationParams params) {
		this.params = params;
		resetMap();
	}
	
	@Autowired
	public void setRandom(Random random) {
		this.random = random;
	}
	
	@Autowired
	public void setMazegen(MazeGenerator mazegen) {
		this.mazegen = mazegen;
	}
	
	public void setPrioritiesPromotion(boolean prioritiesPromotion) {
		this.prioritiesPromotion = prioritiesPromotion;
	}
	
	public void setTimeWindowScaling(boolean timeWindowScaling) {
		this.timeWindowScaling = timeWindowScaling;
	}
	
	public TileMap getMap() {
		return map;
	}
	
	public List<MobileRobot> getRobots() {
		return robots;
	}
	
	public WHCASimulationParams getParams() {
		return params;
	}
	
	synchronized void resetMap() {
		map = new TileMap(params.mapSizeW, params.mapSizeH);
		robots.clear();
	}
	
	public synchronized void placeRobots() {
		robots.clear();
		for (int i = 0; i < params.robotsCount; i++) {
			Point cell = randomUnoccupiedCellForRobot(map);
			if (cell == null)
				throw new NoNextFieldException("can't find next random robot posistion - all seems to be occupied");
			createMobileRobot(cell);
		}
	}
	
	public synchronized MobileRobot createMobileRobot(Point point) {
		int id = nextRobotId(robots);
		MobileRobot robo = new MobileRobot(point, robot -> onTargetReached(robot), id, id);
		robots.add(robo);
		return robo;
	}
	
	private int nextRobotId(List<MobileRobot> robots) {
		return robots.stream().mapToInt(robot -> robot.getId()).max().orElse(0) + 1;
	}
	
	private void onTargetReached(MobileRobot robot) {
		if (params.robotAutoTarget) {
			if (robot.getTarget() == null || robot.hasReachedTarget()) {
				logger.info("robot: " + robot + " - assigning new target");
				randomRobotTarget(robot);
				reorderNeeded = true;
			}
		}
	}
	
	public synchronized void randomTargetPressed() {
		for (MobileRobot robot : robots) {
			robot.setTarget(null); // clear targets - not to block each other during randoming
		}
		for (MobileRobot robot : robots) {
			randomRobotTarget(robot);
		}
		reorderNeeded = true;
	}
	
	public synchronized void generateMaze() {
		mazegen.generateMaze(map);
	}
	
	public boolean isCalculatingPaths() {
		return calculatingPaths;
	}
	
	public synchronized void setRobots(List<MobileRobot> robots) {
		this.robots = robots;
	}
	
	MobileRobot occupiedByRobot(Point point) {
		for (MobileRobot robot : robots) {
			if (robot.getPosition().equals(point))
				return robot;
		}
		return null;
	}
	
	private void randomRobotTarget(MobileRobot robot) {
		robot.resetNextMoves();
//		Point start = robot.lastTarget();
		Point target = randomUnoccupiedCellForTarget(map);
		// reset its initial priority
		robot.setPriority(robot.getId());
		if (target == null)
			throw new NoNextFieldException("can't find next random robot target - all seems to be occupied");
		robot.setTarget(target);
	}
	
	private Point randomUnoccupiedCellForTarget(TileMap map) {
		// get all unoccupied cells
		List<Point> frees = new ArrayList<>();
		map.foreach((x, y, occupied) -> {
			if (!occupied)
				frees.add(new Point(x, y));
		});
		// remove occupied by other targets
		for (MobileRobot robot : robots) {
			Point target = robot.getTarget();
			if (target != null)
				frees.remove(target);
		}
		if (frees.isEmpty())
			return null;
		// random from list
		return frees.get(random.nextInt(frees.size()));
	}
	
	private Point randomUnoccupiedCellForRobot(TileMap map) {
		// get all unoccupied cells
		List<Point> frees = new ArrayList<>();
		map.foreach((x, y, occupied) -> {
			if (!occupied)
				frees.add(new Point(x, y));
		});
		// remove occupied by other robots
		for (MobileRobot robot : robots) {
			Point p = robot.getPosition();
			if (p != null)
				frees.remove(p);
		}
		if (frees.isEmpty())
			return null;
		// random from list
		return frees.get(random.nextInt(frees.size()));
	}
	
	
	public synchronized void stepSimulation() {
//		logger.debug("next simulation step...");
		boolean replan = false;
		long startTime = System.currentTimeMillis();
//		logger.debug("collision detection (before)...");
		resetAllCollidedRobots();
		robotsReached.clear();
//		logger.debug("moving robots...");
		for (MobileRobot robot : robots) {
			if (robot.hasNextMove()) {
				robot.setPosition(robot.pollNextMove());
			}
			if (robot.hasReachedTarget() && params.robotAutoTarget) {
				robotsReached.add(robot);
				replan = true;
			} else if (!robot.hasNextMove() && !robot.hasReachedTarget()) {
//				logger.debug("robot: " + robot.getId() + " has no planned moves, replanning needed");
				replan = true;
			}
		}
		for (MobileRobot robot : robotsReached) {
			robot.targetReached();
		}
		
		List<Path> paths = new ArrayList<>();
		if (replan) {
//			logger.debug("replanning all paths...");
			paths = findPaths();
		}
//		logger.debug("collision detection (after)...");
		resetAllCollidedRobots();
		
		if (replan) {
		  System.out.println("Solving Time = " + (System.currentTimeMillis() - startTime) + " ms");
		  
		  int makespan = 0;
		  int moves = 0;
		  for (Path path : paths) {
	      int move = 0;
		    for (int i = 0; i < path.getLength() - 1; i++) {
		      if (path.getT(i) != i) {
		        System.out.println("WARN: Timestep is not in order!");
		      }
		      if (path.getX(i) != path.getX(i + 1) || path.getY(i) != path.getY(i + 1)) {
		        move++;
		      }
		    }
		    makespan = (move > makespan)? move : makespan;
		    moves += move;
//        System.out.println(path);
//        System.out.println(move);
		  }
		  
		  System.out.println("Makespan = " + makespan);
		  System.out.println("Moves = " + moves);
		  System.out.println("Window size = " + paths.get(0).getLength());
		}
	}
	
	synchronized List<Path> findPaths() {
	  List<Path> paths = new ArrayList<>();
		calculatingPaths = true;
		params.readFromUI();
		int tDim = params.timeDimension;
		TileMap map2 = new TileMap(map);
		ReservationTable reservationTable = new ReservationTable(map2.getWidthInTiles(), map2.getHeightInTiles(), tDim);
		map2.foreach((x, y, occupied) -> {
			if (occupied)
				reservationTable.setBlocked(x, y);
		});

		reorderNeeded = true; // TODO reorder only when needed
		if (reorderNeeded) {
			Collections.sort(robots, robotsPriorityComparator);
//			logger.debug("the new order: " + Joiner.on(", ").join(robots));
			reorderNeeded = false;
		}

		for (MobileRobot robot : robots) {
		  paths.add(findPath(robot, reservationTable, map));
		}
		calculatingPaths = false;
		return paths;
	}
	
	public Path findPath(MobileRobot robot, ReservationTable reservationTable, TileMap map) {
//		logger.info("robot: " + robot.getId() + " - planning path");
		robot.resetMovesQue();
		Point start = robot.getPosition();
		Point target = robot.getTarget();
		Path path = new Path();
		
		if (target != null) {
			WHCAPathFinder pathFinder = new WHCAPathFinder(reservationTable, map);
			path = pathFinder.findPath(start.getX(), start.getY(), target.getX(), target.getY());
//			logger.debug("path planned (" + robot.toString() + "): " + path);
			if (path != null) {
				// enque path
				int t = 0;
				reservationTable.setBlocked(start.x, start.y, t);
				reservationTable.setBlocked(start.x, start.y, t + 1);
				Path.Step step = null;
				for (int i = 1; i < path.getLength(); i++) {
					step = path.getStep(i);
					robot.enqueueMove(step.getX(), step.getY());
					t++;
					reservationTable.setBlocked(step.getX(), step.getY(), t);
					reservationTable.setBlocked(step.getX(), step.getY(), t + 1);
				}
				// fill the rest with last position
				if (step != null) {
					for (int i = t + 1; i < reservationTable.getTimeDimension(); i++) {
						reservationTable.setBlocked(step.getX(), step.getY(), i);
					}
				}
				// cant find a way - it's waiting, then promote its priority
				if (path.getLength() <= 1) {
					promotePriority(robot, " - due to path not found");
				}
			} else {
				logger.warn("path not found due to static obstacles");
				reservationTable.setBlocked(start.x, start.y);
			}
		}
		return path;
	}
	
	private void resetAllCollidedRobots() {
		int iterations = 1;
		while (resetCollidedRobots()) {
			iterations++;
		}
	}
	
	private boolean resetCollidedRobots() {
		boolean collisionHappened = false;
		List<Pair<MobileRobot, MobileRobot>> collidedRobots = new ArrayList<>();
		for (MobileRobot robot : robots) {
			MobileRobot collidedRobot = collisionDetected(robot);
			if (collidedRobot != null) {
//				logger.debug("Collision detected between robots: " + robot.getId() + ", " + collidedRobot.getId());
				collidedRobots.add(new Pair<>(robot, collidedRobot));
				collisionHappened = true;
//				logger.debug("robot " + robot.getId() + " previous path: " + robot.getMovesQue());
//				logger.debug("collidedRobot " + collidedRobot.getId() + " previous path: " + collidedRobot.getMovesQue());
			}
		}
		for (Pair<MobileRobot, MobileRobot> pair : collidedRobots) {
			MobileRobot first = pair.getKey();
			MobileRobot second = pair.getValue();
			first.resetMovesQue();
			second.resetMovesQue();
//			MobileRobot minorPriority = first.getPriority() < second.getPriority() ? first : second;
//			MobileRobot majorPriority = first.getPriority() < second.getPriority() ? second : first;
//			  // priority promotion for robot with minor priority
//			promotePriority(minorPriority, " - due to collision");
//			promotePriority(majorPriority, " - due to collision");
//			majorPriority.setPriority(majorPriority.getPriority() - 1);
		}
		return collisionHappened;
	}
	
	private MobileRobot collisionDetected(MobileRobot robot) {
		for (MobileRobot otherRobot : robots) {
			if (otherRobot == robot)
				continue;
			if (otherRobot.nearestTarget().equals(robot.nearestTarget())) {
				return otherRobot;
			}
		}
		return null;
	}
	
	private void promotePriority(MobileRobot robot, String reason) {
		if (!prioritiesPromotion)
			return;
		robot.setPriority(robot.getPriority() + 1);
		reorderNeeded = true;
//		logger.debug("robot " + robot.getId() + " promoted to priority " + robot.getPriority() + reason);
		if (robot.getPriority() > params.timeDimension && timeWindowScaling) {
			params.timeDimension = robot.getPriority();
			params.sendToUI();
//			logger.debug("Time dimension increased to " + params.timeDimension);
		}
	}
	
}
