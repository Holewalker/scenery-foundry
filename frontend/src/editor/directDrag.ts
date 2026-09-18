import { Plane, Ray, Vector3 } from 'three'

export function intersectHorizontalDragPlane(origin: Vector3, direction: Vector3, y: number): Vector3 | null {
  if (![...origin.toArray(), ...direction.toArray(), y].every(Number.isFinite)) return null
  const ray = new Ray(origin, direction.clone().normalize())
  const point = ray.intersectPlane(new Plane(new Vector3(0, 1, 0), -y), new Vector3())
  return point && point.toArray().every(Number.isFinite) ? point : null
}
