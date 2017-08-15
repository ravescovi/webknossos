// @flow

import update from "immutability-helper";

import type { OxalisState } from "oxalis/store";
import type { ActionType } from "oxalis/model/actions/actions";

function AnnotationReducer(state: OxalisState, action: ActionType): OxalisState {
  switch (action.type) {
    case "SET_ANNOTATION_NAME": {
      return update(state, {
        tracing: {
          name: { $set: action.name },
        },
      });
    }

    case "SET_ANNOTATION_PUBLIC": {
      return update(state, {
        tracing: {
          isPublic: { $set: action.isPublic },
        },
      });
    }

    default:
      return state;
  }
}

export default AnnotationReducer;