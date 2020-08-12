/**
 * camera_controller.js
 * @flow
 */

import * as React from "react";
import * as THREE from "three";
import TWEEN from "tween.js";
import _ from "lodash";

import {
  type OrthoView,
  type OrthoViewMap,
  type OrthoViewRects,
  OrthoViewValuesWithoutTDView,
  OrthoViews,
  type Vector3,
} from "oxalis/constants";
import { V3 } from "libs/mjs";
import {
  getDatasetExtentInLength,
  getDatasetCenter,
} from "oxalis/model/accessors/dataset_accessor";
import { getInputCatcherAspectRatio } from "oxalis/model/accessors/view_mode_accessor";
import {
  getPlaneExtentInVoxelFromStore,
  getPosition,
} from "oxalis/model/accessors/flycam_accessor";
import { listenToStoreProperty } from "oxalis/model/helpers/listener_helpers";
import { setTDCameraAction } from "oxalis/model/actions/view_mode_actions";
import { voxelToNm, getBaseVoxel } from "oxalis/model/scaleinfo";
import Store, { type CameraData } from "oxalis/store";
import api from "oxalis/api/internal_api";
import getSceneController from "oxalis/controller/scene_controller_provider";

type Props = {
  cameras: OrthoViewMap<THREE.OrthographicCamera>,
  onCameraPositionChanged: () => void,
};

class CameraController extends React.PureComponent<Props> {
  storePropertyUnsubscribers: Array<Function>;

  componentDidMount() {
    const far = 8000000;
    for (const cam of _.values(this.props.cameras)) {
      cam.near = 0;
      cam.far = far;
    }

    Store.dispatch(
      setTDCameraAction({
        near: 0,
        far,
      }),
    );

    this.bindToEvents();
    api.tracing.rotate3DViewToDiagonal(false);
  }

  componentWillUnmount() {
    this.storePropertyUnsubscribers.forEach(fn => fn());
  }

  // Non-TD-View methods

  updateCamViewport(inputCatcherRects?: OrthoViewRects): void {
    const state = Store.getState();
    const { clippingDistance } = state.userConfiguration;
    const scaleFactor = getBaseVoxel(state.dataset.dataSource.scale);
    for (const planeId of OrthoViewValuesWithoutTDView) {
      const [width, height] = getPlaneExtentInVoxelFromStore(
        state,
        state.flycam.zoomStep,
        planeId,
      ).map(x => x * scaleFactor);

      this.props.cameras[planeId].left = -width / 2;
      this.props.cameras[planeId].right = width / 2;

      this.props.cameras[planeId].bottom = -height / 2;
      this.props.cameras[planeId].top = height / 2;

      this.props.cameras[planeId].near = -clippingDistance;
      this.props.cameras[planeId].updateProjectionMatrix();
    }

    if (inputCatcherRects != null) {
      // Update td camera's aspect ratio
      const tdCamera = this.props.cameras[OrthoViews.TDView];

      const oldMid = (tdCamera.right + tdCamera.left) / 2;
      const oldWidth = tdCamera.right - tdCamera.left;
      const oldHeight = tdCamera.top - tdCamera.bottom;

      const oldAspectRatio = oldWidth / oldHeight;
      const tdRect = inputCatcherRects[OrthoViews.TDView];
      const newAspectRatio = tdRect.width / tdRect.height;

      // Do not update the tdCamera if the tdView is not visible (height === 0)
      if (Number.isNaN(newAspectRatio)) return;

      const newWidth = (oldWidth * newAspectRatio) / oldAspectRatio;

      tdCamera.left = oldMid - newWidth / 2;
      tdCamera.right = oldMid + newWidth / 2;
      tdCamera.updateProjectionMatrix();
    }
  }

  update(): void {
    const state = Store.getState();
    const gPos = getPosition(state.flycam);
    // camera position's unit is nm, so convert it.
    const cPos = voxelToNm(state.dataset.dataSource.scale, gPos);
    const cPosVec = new THREE.Vector3(cPos[0], cPos[1], cPos[2]);
    this.props.cameras[OrthoViews.PLANE_XY].position.copy(cPosVec);
    this.props.cameras[OrthoViews.PLANE_YZ].position.copy(cPosVec);
    this.props.cameras[OrthoViews.PLANE_XZ].position.copy(cPosVec);
  }

  bindToEvents() {
    this.storePropertyUnsubscribers = [
      listenToStoreProperty(
        storeState => storeState.userConfiguration.clippingDistance,
        () => this.updateCamViewport(),
        true,
      ),
      listenToStoreProperty(
        storeState => storeState.flycam.zoomStep,
        () => this.updateCamViewport(),
      ),
      listenToStoreProperty(
        storeState => storeState.viewModeData.plane.inputCatcherRects,
        inputCatcherRects => this.updateCamViewport(inputCatcherRects),
      ),
      listenToStoreProperty(
        storeState => storeState.flycam.currentMatrix,
        () => this.update(),
        true,
      ),
      listenToStoreProperty(
        storeState => storeState.viewModeData.plane.tdCamera,
        cameraData => this.updateTDCamera(cameraData),
        true,
      ),
    ];
  }

  // TD-View methods

  updateTDCamera(cameraData: CameraData): void {
    const tdCamera = this.props.cameras[OrthoViews.TDView];

    tdCamera.position.set(...cameraData.position);
    tdCamera.left = cameraData.left;
    tdCamera.right = cameraData.right;
    tdCamera.top = cameraData.top;
    tdCamera.bottom = cameraData.bottom;
    tdCamera.up = new THREE.Vector3(...cameraData.up);
    tdCamera.lookAt(new THREE.Vector3(...cameraData.lookAt));

    tdCamera.updateProjectionMatrix();

    this.props.onCameraPositionChanged();
  }

  render() {
    return null;
  }
}

type TweenState = {
  upX: number,
  upY: number,
  upZ: number,
  xPos: number,
  yPos: number,
  zPos: number,
  left: number,
  right: number,
  top: number,
  bottom: number,
};

export function rotate3DViewTo(id: OrthoView, animate: boolean = true): void {
  const state = Store.getState();
  const { dataset } = state;
  const { tdCamera } = state.viewModeData.plane;
  const flycamPos = voxelToNm(dataset.dataSource.scale, getPosition(state.flycam));
  const datasetExtent = getDatasetExtentInLength(dataset);
  // This distance ensures that the 3D camera is so far "in the back" that all elements in the scene
  // are in front of it and thus visible.
  const clippingOffsetFactor = Math.max(datasetExtent.width, datasetExtent.height, datasetExtent.depth);
  // Use width and height to keep the same zoom.
  let width = tdCamera.right - tdCamera.left;
  let height = tdCamera.top - tdCamera.bottom;

  let position: Vector3;
  let up: Vector3;
  // Way to calculate the position and rotation of the camera:
  // First, the camera is either positioned at the current center of the flycam or in the dataset center.
  // Second, the camera is moved backwards by a clipping offset into the wanted direction.
  // Together with matching lookUp (up) vectors and keeping the width and height, the position and rotation updates correctly.
  if (id === OrthoViews.TDView && (height <= 0 || width <= 0)) {
    // This should only be the case when initializing the 3D-viewport.
    const aspectRatio = getInputCatcherAspectRatio(state, OrthoViews.TDView);
    const datasetCenter = voxelToNm(dataset.dataSource.scale, getDatasetCenter(dataset));
    // The camera has no width and height which might be due to a bug or the camera has not been initialized.
    // Thus we zoom out to show the whole dataset.
    const paddingFactor = 1.1;
    width =
      Math.sqrt(
        datasetExtent.width * datasetExtent.width + datasetExtent.height * datasetExtent.height,
      ) * paddingFactor;
    height = width / aspectRatio;
    up = [0, 0, -1];
    // For very tall datasets that have a very low or high z starting coordinate, the planes might not be visible.
    // Thus take the z coordinate of the flycam instead of the z coordinate of the center.
    // The clippingOffsetFactor is added in x and y direction to get a view on the dataset the 3D view that is close to the plane views.
    // Thus the rotation between the 3D view to the eg. XY plane views is much shorter and the interpolated rotation does not look weird.
    position = [
      datasetCenter[0] + clippingOffsetFactor,
      datasetCenter[1] + clippingOffsetFactor,
      flycamPos[2] - clippingOffsetFactor,
    ];
  } else if (id === OrthoViews.TDView) {
    position = [
      flycamPos[0] + clippingOffsetFactor,
      flycamPos[1] + clippingOffsetFactor,
      flycamPos[2] - clippingOffsetFactor,
    ];
    up = [0, 0, -1];
  } else {
    const positionOffset: OrthoViewMap<Vector3> = {
      [OrthoViews.PLANE_XY]: [0, 0, -clippingOffsetFactor],
      [OrthoViews.PLANE_YZ]: [clippingOffsetFactor, 0, 0],
      [OrthoViews.PLANE_XZ]: [0, clippingOffsetFactor, 0],
    };
    const upVector: OrthoViewMap<Vector3> = {
      [OrthoViews.PLANE_XY]: [0, -1, 0],
      [OrthoViews.PLANE_YZ]: [0, -1, 0],
      [OrthoViews.PLANE_XZ]: [0, 0, -1],
    };
    up = upVector[id];
    position = [
      positionOffset[id][0] + flycamPos[0],
      positionOffset[id][1] + flycamPos[1],
      positionOffset[id][2] + flycamPos[2],
    ];
  }

  const currentFlycamPos = voxelToNm(
    Store.getState().dataset.dataSource.scale,
    getPosition(Store.getState().flycam),
  ) || [0, 0, 0];


  // const diff = V3.sub(tdCamera.position, position);
  // const v1 = V3.normalize(diff);
  // const v2 = V3.normalize(V3.cross(up, v1));
  // const center = V3.scale(V3.add(tdCamera.position, position), 1/2);
  // const radius = V3.length(diff) / 2;

  // const diff = V3.sub(tdCamera.position, position);
  const v1Unnormalized = V3.sub(currentFlycamPos, position);
  let v2Unnormalized = V3.sub(currentFlycamPos, tdCamera.position);
  v2Unnormalized = V3.cross(V3.cross(v1Unnormalized, v2Unnormalized), v1Unnormalized);
  const v1 = V3.normalize(v1Unnormalized);
  const v2 = V3.normalize(v2Unnormalized);

  const center = currentFlycamPos;
  const radius = Math.min(V3.length(v1Unnormalized), V3.length(v2Unnormalized));

  const points = [];

  let multiplier = 1;
  // const posDiff = V3.length(V3.sub(currentFlycamPos, calculateCirclePoint(0.5)))
  // const negDiff = V3.length(V3.sub(currentFlycamPos, calculateCirclePoint(-0.5)))

  // const usePositiveCircleHalf = posDiff > negDiff;
  // multiplier = usePositiveCircleHalf ? 1 : -1;
  // console.log("usePositiveCircleHalf", usePositiveCircleHalf)
  function calculateCirclePoint(t) {
    const a = V3.scale(v1, radius * Math.cos(Math.PI * t * multiplier));
    const b = V3.scale(v2, radius * Math.sin(Math.PI * t * multiplier));
    const p = V3.add(V3.add(center, a), b);
    return p;
  }

  for (let t = 0; t < 1.0; t += 1 / 25) {
    points.push(calculateCirclePoint(t));
  }

  getSceneController().drawPoints(points);
  console.log("drawPoints", points)

  const to: TweenState = {
    xPos: position[0],
    yPos: position[1],
    zPos: position[2],
    upX: up[0],
    upY: up[1],
    upZ: up[2],
    left: -width / 2,
    right: width / 2,
    top: height / 2,
    bottom: -height / 2,
  };

  const updateCameraTDView = (tweenState: TweenState, t) => {
    let { xPos, yPos, zPos, upX, upY, upZ, left, right, top, bottom } = tweenState;

    const p = calculateCirclePoint(t)
    const currentFlycamPos = voxelToNm(
      Store.getState().dataset.dataSource.scale,
      getPosition(Store.getState().flycam),
    );

    if (!isNaN(p[0])) {
      xPos = p[0];
      yPos = p[1];
      zPos = p[2];
    }


    Store.dispatch(
      setTDCameraAction({
        position: [xPos, yPos, zPos],
        up: [upX, upY, upZ],
        left,
        right,
        top,
        bottom,
        lookAt: currentFlycamPos,
      }),
    );
  };

  if (animate) {
    const from: TweenState = {
      upX: tdCamera.up[0],
      upY: tdCamera.up[1],
      upZ: tdCamera.up[2],
      xPos: tdCamera.position[0],
      yPos: tdCamera.position[1],
      zPos: tdCamera.position[2],
      left: tdCamera.left,
      right: tdCamera.right,
      top: tdCamera.top,
      bottom: tdCamera.bottom,
    };
    const tween = new TWEEN.Tween(from);

    const time = 800;

    tween
      .to(to, time)
      .onUpdate(function updater(t) {
        // TweenJS passes the current state via the `this` object.
        // However, for better type checking, we pass it as an explicit
        // parameter.
        updateCameraTDView(this, t);
      })
      .start();
  } else {
    updateCameraTDView(to);
  }
}

export default CameraController;
