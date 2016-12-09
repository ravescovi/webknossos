_                   = require("lodash")
Marionette          = require("backbone.marionette")
Toast               = require("libs/toast")
Request             = require("libs/request")
SelectionView       = require("admin/views/selection_view")
TeamCollection      = require("admin/models/team/team_collection")
DatastoreCollection = require("admin/models/datastore/datastore_collection")

class DatasetUploadView extends Marionette.View

  template : _.template("""
    <div class="row">
      <div class="col-md-6">
        <h3>Upload Dataset</h3>
        <form action="/api/datasets/upload" method="POST" class="form-horizontal" enctype="multipart/form-data">
          <div class="form-group">
            <label class="col-sm-3 control-label" for="name">Name</label>
            <div class="col-sm-9">
            <input type="text" required name="name" value="" class="form-control" autofocus pattern="^[0-9a-zA-Z_\-]+$" title="Dataset names may only contain letters, numbers, _ and -">
              <span class="help-block errors"></span>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-3 control-label" for="team">Team</label>
            <div class="col-sm-9 team">
              <span class="help-block errors"></span>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-3 control-label" for="scale_scale">Scale</label>
            <div class="col-sm-9">
              <input type="text" required name="scale.scale" value="12.0, 12.0, 24.0" class="form-control" pattern="\\s*([0-9]+(?:\.[0-9]+)?),\\s*([0-9]+(?:\\.[0-9]+)?),\\s*([0-9]+(?:\\.[0-9]+)?)\\s*" title="Specify dataset scale like &quot;XX, YY, ZZ&quot;">
              <span class="help-block errors"></span>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-3 control-label" for="datastore">Datastore</label>
            <div class="col-sm-9 datastore">
              <span class="help-block errors"></span>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-3 control-label" for="zipFile">Dataset ZIP File</label>
            <div class="col-sm-9">

              <div class="fileinput fileinput-new input-group" data-provides="fileinput">
                <div class="form-control" data-trigger="fileinput">
                  <i class="fa fa-file fileinput-exists"></i>
                  <span class="fileinput-filename"></span>
                </div>
                <span class="input-group-addon btn btn-default btn-file">
                  <span class="fileinput-new">Browse...</span>
                  <span class="fileinput-exists">Change</span>
                  <input type="file" required accept="application/zip" name="zipFile">
                </span>
                <a href="#" class="input-group-addon btn btn-default fileinput-exists" data-dismiss="fileinput">Remove</a>
              </div>
            </div>
          </div>
          <div class="form-group">
            <div class="col-sm-9 col-sm-offset-3">
              <button type="submit" class="form-control btn btn-primary">
                <i class="fa fa-spinner fa-spin hidden"/>
                Import
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  """)

  className : "container dataset-administration"

  regions :
    "team" : ".team"
    "datastore" : ".datastore"

  events :
    "submit form" : "uploadDataset"
    "change input[type=file]" : "createProject"

  ui :
    form : "form"
    spinner : ".fa-spinner"

  initialize : ->

    @teamSelectionView = new SelectionView(
      collection : new TeamCollection()
      name : "team"
      childViewOptions :
        modelValue: -> return "#{@model.get("name")}"
      data : "amIAnAdmin=true"
    )

    @datastoreSelectionView = new SelectionView(
      collection : new DatastoreCollection()
      name : "datastore"
      filter : (item) -> item.get("url") != null
      childViewOptions :
        modelValue: -> return "#{@model.get("url")}"
        modelLabel: -> return "#{@model.get("name")}"
    )



  onRender : ->

    @showChildView("team", @teamSelectionView)
    @showChildView("datastore", @datastoreSelectionView)


  uploadDataset : (evt) ->

    evt.preventDefault()
    form = @ui.form[0]

    if form.checkValidity()

      Toast.info("Uploading datasets", false)
      @ui.spinner.removeClass("hidden")

      Request.receiveJSON("/api/dataToken/generate")
      .then(({ token }) ->
        return Request.sendMultipartFormReceiveJSON("/data/datasets?token=#{token}", {
          data : new FormData(form)
          host : form.datastore.value
        })
      )
      .then(
        ->
          Toast.success()
          app.router.navigate("/dashboard", { trigger: true })
        -> # NOOP
      )
      .then(
        => # always do
          @ui.spinner.addClass("hidden")
      )


module.exports = DatasetUploadView
