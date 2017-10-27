// @flow
import React from "react";
import { Form, Input, Select, Button, Card, Spin, Upload, Icon } from "antd";
import app from "app";
import { getTeams, getDatastores } from "admin/admin_rest_api";
import Toast from "libs/toast";
import Request from "libs/request";
import messages from "messages";
import type { APITeamType, APIDatastoreType } from "admin/api_flow_types";

const FormItem = Form.Item;
const Option = Select.Option;

type Props = {
  form: Object,
};

type State = {
  teams: Array<APITeamType>,
  datastores: Array<APIDatastoreType>,
  isUploading: boolean,
};

class DatasetUploadView extends React.PureComponent<Props, State> {
  state = {
    teams: [],
    datastores: [],
    isUploading: false,
  };

  componentDidMount() {
    this.fetchData();
  }

  async fetchData() {
    const datastores = await getDatastores();
    const teams = await getTeams();
    const currentUserAdminTeams = app.currentUser.teams
      .filter(team => team.role.name === "admin")
      .map(team => team.team);

    this.setState({
      datastores,
      teams: teams.filter(team => currentUserAdminTeams.includes(team.name)),
    });
  }

  normFile = e => {
    if (Array.isArray(e)) {
      return e;
    }
    return e && e.fileList;
  };

  handleSubmit = evt => {
    evt.preventDefault();

    this.props.form.validateFields(async (err, formValues) => {
      if (!err) {
        Toast.info("Uploading datasets", false);
        this.setState({
          isUploading: true,
        });

        Request.receiveJSON("/api/dataToken/generate")
          .then(({ token }) =>
            Request.sendMultipartFormReceiveJSON(`/data/datasets?token=${token}`, {
              data: formValues,
              host: formValues.datastore,
            }),
          )
          .then(
            () => {
              Toast.success(messages["dataset.upload_success"]);
              const url = `/datasets/${formValues.name}/import`;
              app.router.navigate(url, { trigger: true });
            },
            () => {
              this.setState({ isUploading: false });
            },
          );
      }
    });
  };

  render() {
    const { getFieldDecorator } = this.props.form;

    return (
      <div className="dataset-administration" style={{ padding: 5 }}>
        <Spin spinning={this.state.isUploading} size="large">
          <Card title={<h3>Upload Dataset</h3>}>
            <Form onSubmit={this.handleSubmit} layout="vertical">
              <FormItem label="Dataset Name" hasFeedback>
                {getFieldDecorator("name", {
                  rules: [{ required: true }, { min: 3 }, { pattern: /[0-9a-zA-Z_-]+$/ }],
                })(<Input autoFocus />)}
              </FormItem>

              <FormItem label="Team" hasFeedback>
                {getFieldDecorator("team", {
                  rules: [{ required: true }],
                })(
                  <Select
                    showSearch
                    placeholder="Select a Team"
                    optionFilterProp="children"
                    style={{ width: "100%" }}
                  >
                    {this.state.teams.map((team: APITeamType) => (
                      <Option key={team.id} value={team.name}>
                        {`${team.name}`}
                      </Option>
                    ))}
                  </Select>,
                )}
              </FormItem>

              <FormItem label="Datastore" hasFeedback>
                {getFieldDecorator("datastore", {
                  rules: [{ required: true }],
                })(
                  <Select
                    showSearch
                    placeholder="Select a Team"
                    optionFilterProp="children"
                    style={{ width: "100%" }}
                  >
                    {this.state.datastores.map((datastore: APIDatastoreType) => (
                      <Option key={datastore.name} value={datastore.url}>
                        {`${datastore.name}`}
                      </Option>
                    ))}
                  </Select>,
                )}
              </FormItem>

              <FormItem label="Dataset ZIP File" hasFeedback>
                {getFieldDecorator("zipFile", {
                  rules: [{ required: true }],
                  valuePropName: "fileList",
                  getValueFromEvent: this.normFile,
                })(
                  <Upload.Dragger
                    name="files"
                    beforeUpload={file => {
                      this.props.form.setFieldsValue({ zipFile: [file] });
                      return false;
                    }}
                  >
                    <p className="ant-upload-drag-icon">
                      <Icon type="inbox" />
                    </p>
                    <p className="ant-upload-text">Click or Drag File to This Area to Upload</p>
                  </Upload.Dragger>,
                )}
              </FormItem>

              <FormItem>
                <Button type="primary" htmlType="submit">
                  Upload
                </Button>
              </FormItem>
            </Form>
          </Card>
        </Spin>
      </div>
    );
  }
}

export default Form.create()(DatasetUploadView);
