### define
libs/utils : Utils
backbone.marionette : marionette
backbone.subviews : subviews
underscore : _
./category_views/colors_category_view : ColorsCategoryView
./category_views/quality_category_view : QualityCategoryView
###

class DatasetSettingsView extends Backbone.Marionette.ItemView


  id : "dataset-settings"


  template : _.template("""
    <div class="panel-group accordion">

      <% _.forEach(subviewCreators, function (subview, key) { %>
        <div data-subview="<%= key %>"></div>
      <% }) %>

    </div>
  """)


  subviewCreators :

    "colors-controls" : ->

      return new ColorsCategoryView({ @model })

    "quality-controls" : ->

      return new QualityCategoryView({ @model })


  initialize : ({ @_model }) ->

    @listenTo(app.vent, "model:sync", ->
      @model = @_model.dataset
      @render()
    )

    Backbone.Subviews.add(this)


  render : ->

    if @model
      super()
    else
      @$el.html(Utils.loaderTemplate())


  serializeData : ->

    return { @subviewCreators }
