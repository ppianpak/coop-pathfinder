package igrek.robopath.pathfinder.mystar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class My3DPathFinder {
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	
	/** The set of nodes that have been searched through */
	private List<Node> closed = new ArrayList<>();
	/** The set of nodes that we do not yet consider fully searched */
	private SortedList<Node> open = new SortedList<>();
	
	private ReservationTable reservation;
	
	/** The complete set of nodes across the map */
	private Node[][][] nodes;
	
	/**
	 * Create a path finder with the default heuristic - closest to target.
	 */
	public My3DPathFinder(ReservationTable reservation) {
		this.reservation = reservation;
	}
	
	/**
	 * Find a path from the starting location provided (sx,sy) to the target
	 * location (tx,ty) avoiding blockages and attempting to honour costs
	 * provided by the tile map.
	 * @param sx The x coordinate of the start location
	 * @param sy The y coordinate of the start location
	 * @param tx The x coordinate of the target location
	 * @param ty Teh y coordinate of the target location
	 * @return The path found from start to end, or null if no path can be found.
	 */
	public Path findPath(int sx, int sy, int tx, int ty) {
		// initial state for A*. The closed group is empty. Only the starting
		// tile is in the open list and it'e're already there
		closed.clear();
		open.clear();
		
		nodes = new Node[reservation.getWidth()][reservation.getHeight()][reservation.getTimeDimension()];
		for (int x = 0; x < reservation.getWidth(); x++) {
			for (int y = 0; y < reservation.getHeight(); y++) {
				for (int t = 0; t < reservation.getTimeDimension(); t++) {
					nodes[x][y][t] = new Node(x, y, t);
				}
			}
		}
		
		for (int t = 0; t < reservation.getTimeDimension(); t++) {
			nodes[tx][ty][t].setParent(null);
			nodes[sx][sy][t].setCost(0);
		}
		//Dodajemy pole startowe (lub węzeł) do Listy Otwartych.
		open.add(nodes[sx][sy][0]);
		
		// first check, if the destination is blocked, we can't get there
		if (reservation.isBlocked(tx, ty))
			return null;
		//jeśli punkt docelowy jest punktem startowym - brak ścieżki
		if (sx == tx && sy == ty)
			return null;
		
		//dopóki lista otwartych nie jest pusta
		while (!open.isEmpty()) {
			// pull out the first node in our open list, this is determined to
			// be the most likely to be the next step based on our heuristic
			//Szukamy pola o najniższej wartości F na Liście Otwartych. Czynimy je aktualnym polem
			Node current = open.first();
			//jeśli current jest węzłem docelowym
			if (current.getX() == tx && current.getY() == ty && current.getT() == reservation.getTimeDimension() - 1) {
				// At this point we've definitely found a path so we can uses the parent
				// references of the nodes to find out way from the target location back
				// to the start recording the nodes on the way.
				//Zapisujemy ścieżkę. Krocząc w kierunku od pola docelowego do startowego, przeskakujemy z kolejnych pól na im przypisane pola rodziców, aż do osiągnięcia pola startowego.
				Path path = new Path();
				Node target = nodes[tx][ty][reservation.getTimeDimension() - 1];
				while (target != nodes[sx][sy][0]) {
					path.prependStep(target.getX(), target.getY(), target.getT());
					target = target.getParent();
					if (target == null) {
						logger.warn("dupa");
					}
				}
				path.prependStep(sx, sy, 0);
				return path;
			}
			//Aktualne pole przesuwamy do Listy Zamkniętych.
			open.remove(current);
			closed.add(current);
			// search through all the neighbours of the current node evaluating
			// them as next steps
			//Dla każdego z wybranych przyległych pól (sasiad) do pola aktualnego
			List<Node> neighbours = availableNeighbours(current);
			for (Node neighbour : neighbours) {
				
				//jeśli NIE-MOŻNA go przejść, ignorujemy je.
				if (!isValidLocation(sx, sy, neighbour.getX(), neighbour.getY(), neighbour.getT()))
					continue;
				
				if (!isValidMove(current.getX(), current.getY(), current.getT(), neighbour.getX(), neighbour
						.getY(), neighbour.getT()))
					continue;
				
				// the cost to get to this node is cost the current plus the movement
				// cost to reach this node. Note that the heursitic value is only used
				// in the sorted open list
				float nextStepCost = current.getCost() + getMovementCost(current.getX(), current.getY(), neighbour
						.getX(), neighbour.getY());
				
				// if the new cost we've determined for this node is lower than
				// it has been previously makes sure the node hasn'e've
				// determined that there might have been a better path to get to
				// this node so it needs to be re-evaluated
				if (nextStepCost < neighbour.getCost()) {
					if (open.contains(neighbour)) {
						open.remove(neighbour);
					}
					if (closed.contains(neighbour)) {
						closed.remove(neighbour);
					}
				}
				// if the node hasn't already been processed and discarded then
				// reset it's cost to our current cost and add it as a next possible
				// step (i.e. to the open list)
				if (!open.contains(neighbour) && !closed.contains(neighbour)) {
					neighbour.setCost(nextStepCost);
					neighbour.setHeuristic(getHeuristicCost(neighbour.getX(), neighbour.getY(), neighbour
							.getT(), tx, ty));
					neighbour.setParent(current);
					open.add(neighbour);
				}
				
			}
		}
		
		// since we'e've run out of search there was no path
		return null;
	}
	
	/**
	 * Check if a given location is valid for the supplied mover
	 * @param sx The starting x coordinate
	 * @param sy The starting y coordinate
	 * @param x  The x coordinate of the location to check
	 * @param y  The y coordinate of the location to check
	 * @return True if the location is valid for the given mover
	 */
	protected boolean isValidLocation(int sx, int sy, int x, int y, int t) {
		if (x < 0 || y < 0 || t < 0 || x >= reservation.getWidth() || y >= reservation.getHeight() || t >= reservation
				.getTimeDimension())
			return false;
		
		if ((sx != x) || (sy != y)) {
			if (reservation.isBlocked(x, y, t))
				return false;
		}
		
		return true;
	}
	
	/**
	 * @param sx from x
	 * @param sy from y
	 * @param x  to x
	 * @param y  to y
	 * @return is move allowed
	 */
	protected boolean isValidMove(int sx, int sy, int st, int x, int y, int t) {
		if (!isValidLocation(sx, sy, x, y, t)) {
			return false;
		}
		// diagonal move not possible when one cell is blocked
		int dx = abs(sx - x);
		int dy = abs(sy - y);
		// diagonal move
		if (dx == 1 && dy == 1) {
			if (reservation.isBlocked(x, y, t) || reservation.isBlocked(sx, sy, t) || reservation.isBlocked(sx, y, t) || reservation
					.isBlocked(x, sy, t)) {
				return false;
			}
		}
		
		return true;
	}
	
	private int abs(int x) {
		return x >= 0 ? x : -x;
	}
	
	/**
	 * Get the cost to move through a given location
	 * @param sx The x coordinate of the tile whose cost is being determined
	 * @param sy The y coordiante of the tile whose cost is being determined
	 * @param tx The x coordinate of the target location
	 * @param ty The y coordinate of the target location
	 * @return The cost of movement through the given tile
	 */
	protected float getMovementCost(int sx, int sy, int tx, int ty) {
		//		float dx = tx - sx;
		//		float dy = ty - sy;
		//		return (float) (Math.sqrt((dx * dx) + (dy * dy)));
		return Math.max(Math.abs(tx - sx), Math.abs(ty - sy));
	}
	
	/**
	 * Get the heuristic cost for the given location. This determines in which
	 * order the locations are processed.
	 * @param x  The x coordinate of the tile whose cost is being determined
	 * @param y  The y coordiante of the tile whose cost is being determined
	 * @param tx The x coordinate of the target location
	 * @param ty The y coordinate of the target location
	 * @return The heuristic cost assigned to the tile
	 */
	protected float getHeuristicCost(int x, int y, int t, int tx, int ty) {
		return (float) (Math.hypot(tx - x, ty - y) + ((float) t) / reservation.getTimeDimension() * Math
				.hypot(tx - x, ty - y));
	}
	
	private List<Node> availableNeighbours(Node current) {
		List<Node> neighbours = new LinkedList<>();
		int t = current.getT() + 1;
		if (t < reservation.getTimeDimension()) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					if (dx == 0 && dy == 0)
						continue;
					// determine the location of the neighbour and evaluate it
					int xp = current.getX() + dx;
					int yp = current.getY() + dy;
					// validate out of bounds
					if ((xp < 0) || (yp < 0) || (xp >= reservation.getWidth()) || (yp >= reservation
							.getHeight()))
						continue;
					neighbours.add(nodes[xp][yp][t]);
				}
			}
			// możliwe czekanie w tym samym miejscu - jako ostatnia propozycja
			neighbours.add(nodes[current.getX()][current.getY()][t]);
		}
		return neighbours;
	}
	
}