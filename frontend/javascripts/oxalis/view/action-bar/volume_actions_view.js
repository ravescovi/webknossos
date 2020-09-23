// @flow
import { Button, Radio, Tooltip } from "antd";
import { connect } from "react-redux";
import React, { PureComponent } from "react";

import { type VolumeTool, VolumeToolEnum, type Vector3 } from "oxalis/constants";
import { document } from "libs/window";
import {
  enforceVolumeTracing,
  isVolumeTraceToolDisallowed,
} from "oxalis/model/accessors/volumetracing_accessor";
import { setToolAction, createCellAction } from "oxalis/model/actions/volumetracing_actions";
import ButtonComponent from "oxalis/view/components/button_component";
import { getCurrentResolution } from "oxalis/model/accessors/flycam_accessor";
import Store, { type OxalisState } from "oxalis/store";

// Workaround until github.com/facebook/flow/issues/1113 is fixed
const RadioGroup = Radio.Group;
const RadioButton = Radio.Button;
const ButtonGroup = Button.Group;

type Props = {|
  activeTool: VolumeTool,
  // This component should be updated when the zoom changes.
  // eslint-disable-next-line react/no-unused-prop-types
  zoomStep: number,
  isInMergerMode: boolean,
  activeResolution: Vector3,
|};

const isZoomStepTooHighForTraceTool = () => isVolumeTraceToolDisallowed(Store.getState());

class VolumeActionsView extends PureComponent<Props> {
  componentDidUpdate = (prevProps: Props) => {
    if (!prevProps.isInMergerMode && this.props.isInMergerMode) {
      Store.dispatch(setToolAction(VolumeToolEnum.MOVE));
    }
  };

  handleSetTool = (event: { target: { value: VolumeTool } }) => {
    Store.dispatch(setToolAction(event.target.value));
  };

  handleCreateCell = () => {
    Store.dispatch(createCellAction());
  };

  render() {
    const { activeTool, activeResolution, isInMergerMode } = this.props;
    const hasResolutionWithHigherDimension = activeResolution.some(val => val > 1);
    const multiSliceAnnotationInfoIcon = hasResolutionWithHigherDimension ? (
      <Tooltip title="You are annotating in a low resolution. You might be annotating multiple slices at once.">
        <i className="fas fa-layer-group" />
      </Tooltip>
    ) : null;
    const isTraceToolDisabled = isZoomStepTooHighForTraceTool();
    const traceToolDisabledTooltip = isTraceToolDisabled
      ? "Your zoom is low to use the trace tool. Please zoom in further to use it."
      : "";

    return (
      <div
        onClick={() => {
          if (document.activeElement) document.activeElement.blur();
        }}
      >
        <RadioGroup onChange={this.handleSetTool} value={activeTool} style={{ marginRight: 10 }}>
          <RadioButton value={VolumeToolEnum.MOVE}>Move</RadioButton>

          <Tooltip
            title={
              isInMergerMode ? "Volume annotation is disabled while the merger mode is active." : ""
            }
          >
            <Tooltip title={traceToolDisabledTooltip}>
              <RadioButton
                value={VolumeToolEnum.TRACE}
                disabled={isInMergerMode || isTraceToolDisabled}
              >
                Trace {activeTool === "TRACE" ? multiSliceAnnotationInfoIcon : null}
              </RadioButton>
            </Tooltip>
            <RadioButton value={VolumeToolEnum.BRUSH} disabled={isInMergerMode}>
              Brush {activeTool === "BRUSH" ? multiSliceAnnotationInfoIcon : null}
            </RadioButton>
          </Tooltip>
        </RadioGroup>
        <ButtonGroup>
          <ButtonComponent onClick={this.handleCreateCell}>
            New&nbsp;
            <span style={{ textDecoration: "underline" }}>C</span>ell
          </ButtonComponent>
        </ButtonGroup>
      </div>
    );
  }
}

function mapStateToProps(state: OxalisState): Props {
  return {
    activeTool: enforceVolumeTracing(state.tracing).activeTool,
    zoomStep: state.flycam.zoomStep,
    isInMergerMode: state.temporaryConfiguration.isMergerModeEnabled,
    activeResolution: getCurrentResolution(state),
  };
}

export default connect<Props, {||}, _, _, _, _>(mapStateToProps)(VolumeActionsView);
