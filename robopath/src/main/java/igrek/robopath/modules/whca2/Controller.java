package igrek.robopath.modules.whca2;

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
import igrek.robopath.common.tilemap.TileMap;
import igrek.robopath.mazegenerator.MazeGenerator;
import igrek.robopath.pathfinder.mywhca.MyWHCAPathFinder;
import igrek.robopath.pathfinder.mywhca.Path;
import igrek.robopath.pathfinder.mywhca.ReservationTable;

public class Controller {
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	@Autowired
	private Random random;
	@Autowired
	private MazeGenerator mazegen;
	
	private TileMap map;
	private Comparator<MobileRobot> robotsPriorityComparator = (o1, o2) -> {
		// reversed order
		return Integer.compare(o2.getPriority(), o1.getPriority());
	};
	private List<MobileRobot> robots = new ArrayList<>();
	
	private SimulationParams params;
	private boolean reorderNeeded = false;
	
	public Controller(Presenter presenter, SimulationParams params) {
		this.params = params;
		resetMap();
	}
	
	public TileMap getMap() {
		return map;
	}
	
	public List<MobileRobot> getRobots() {
		return robots;
	}
	
	synchronized void resetMap() {
		map = new TileMap(params.mapSizeW, params.mapSizeH);
		robots.clear();
	}
	
	synchronized void placeRobots() {
		robots.clear();
		for (int i = 0; i < params.robotsCount; i++) {
			Point cell = randomUnoccupiedCellForRobot(map);
			createMobileRobot(cell);
		}
	}
	
	synchronized MobileRobot createMobileRobot(Point point) {
		int id = nextRobotId(robots);
		MobileRobot robo = new MobileRobot(point, robot -> onTargetReached(robot), id, id);
		robots.add(robo);
		return robo;
	}
	
	private int nextRobotId(List<MobileRobot> robots) {
		return robots.stream().mapToInt(robot -> robot.getId()).max().orElse(0) + 1;
	}
	
	private synchronized void onTargetReached(MobileRobot robot) {
		if (params.robotAutoTarget) {
			if (robot.getTarget() == null || robot.hasReachedTarget()) {
				logger.info("robot: " + robot.getId() + " - assigning new target");
				randomRobotTarget(robot);
				Collections.sort(robots, robotsPriorityComparator);
			}
		}
	}
	
	synchronized void randomTargetPressed() {
		for (MobileRobot robot : robots) {
			robot.setTarget(null); // clear targets - not to block each other during randoming
		}
		for (MobileRobot robot : robots) {
			randomRobotTarget(robot);
		}
		Collections.sort(robots, robotsPriorityComparator);
	}
	
	synchronized void generateMaze() {
		mazegen.generateMaze(map);
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
	
	synchronized void findPaths() {
		int tDim = params.timeDimension;
		TileMap map2 = new TileMap(map);
		ReservationTable reservationTable = new ReservationTable(map2.getWidthInTiles(), map2.getHeightInTiles(), tDim);
		map2.foreach((x, y, occupied) -> {
			if (occupied)
				reservationTable.setBlocked(x, y);
		});
		reorderNeeded = true; // FIXME
		if (reorderNeeded) {
			Collections.sort(robots, robotsPriorityComparator);
			logger.debug("the new order: " + Joiner.on(", ").join(robots));
			reorderNeeded = false;
		}
		for (MobileRobot robot : robots) {
			findPath(robot, reservationTable, map);
		}
	}
	
	public void findPath(MobileRobot robot, ReservationTable reservationTable, TileMap map) {
		logger.info("robot: " + robot.getId() + " - planning path");
		robot.resetMovesQue();
		Point start = robot.getPosition();
		Point target = robot.getTarget();
		if (target != null) {
			MyWHCAPathFinder pathFinder = new MyWHCAPathFinder(reservationTable, map);
			Path path = pathFinder.findPath(start.getX(), start.getY(), target.getX(), target.getY());
			logger.info("path: " + path);
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
					logger.debug("priority promotion due to path not found");
					promotePriority(robot);
				}
			} else {
				logger.warn("path is null");
				reservationTable.setBlocked(start.x, start.y);
			}
		}
	}
	
	synchronized void stepSimulation() {
		resetCollidedRobots();
		for (MobileRobot robot : robots) {
			if (robot.hasNextMove()) {
				robot.setPosition(robot.pollNextMove());
			}
		}
		//			if (robot.hasReachedTarget() && params.robotAutoTarget) {
		//				robot.targetReached();
		//			}
		//				replan = true;
		//			} else if (!robot.hasNextMove() && !robot.hasReachedTarget()) {
		//				logger.info("no planned moves - replanning all paths");
		//				replan = true;
		//			}
	}
	
	private void resetCollidedRobots() {
		for (MobileRobot robot : robots) {
			MobileRobot collidedRobot = collisionDetected(robot);
			if (collidedRobot != null) {
				logger.info("collision detected between robots: " + robot.getId() + ", " + collidedRobot
						.getId());
				robot.resetMovesQue();
				collidedRobot.resetMovesQue();
				MobileRobot minorPriority = robot.getPriority() < collidedRobot.getPriority() ? collidedRobot : robot;
				// priority promotion for robot with minor priority
				logger.debug("priority promotion due to collision");
				promotePriority(minorPriority);
			}
		}
	}
	
	private void promotePriority(MobileRobot robot) {
		robot.setPriority(robot.getPriority() + 1);
		reorderNeeded = true;
		logger.info("robot " + robot.getId() + " has been promoted to priority " + robot.getPriority());
	}
	
	private MobileRobot collisionDetected(MobileRobot robot) {
		for (MobileRobot otherRobot : robots) {
			if (otherRobot == robot)
				continue;
			if (otherRobot.getPosition().equals(robot.getPosition()) || otherRobot.nearestTarget()
					.equals(robot.nearestTarget())) {
				return otherRobot;
			}
		}
		return null;
	}
}
