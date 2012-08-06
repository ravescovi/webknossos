### define
libs/flycam : Flycam
libs/flycam2 : Flycam2d
model : Model
libs/Tween : TWEEN_LIB
model/game : Game
###
    
# global View variables
cam2d = null

# constants
# display 512px out of 512px total width and height
#CAM_DISTANCE = 384/2 # alt: 384/2  #alt: 96
VIEWPORT_WIDTH = 380
PLANE_XY = 0
PLANE_YZ = 1
PLANE_XZ = 2
VIEW_3D  = 3

View =
  initialize : ->

    # The "render" div serves as a container for the canvas, that is 
    # attached to it once a renderer has been initalized.
    container = $("#render")
    # Create a 4x4 grid
    WIDTH = (container.width()-48)/2
    HEIGHT = (container.height()-48)/2

    # Initialize main THREE.js components
    colors    = [0xff0000, 0x0000ff, 0x00ff00, 0xffffff]
    @renderer = new Array(4)
    @camera   = new Array(4)
    @scene    = new Array(4)
    for i in [PLANE_XY, PLANE_YZ, PLANE_XZ, VIEW_3D]
      camDistance  = if i==VIEW_3D then 100000 else 40
      @renderer[i] = new THREE.WebGLRenderer({clearColor: colors[i], clearAlpha: 1, antialias: false})
      @camera[i]   = new THREE.OrthographicCamera(-192, 192, 192, -192, -camDistance, camDistance)
      @scene[i]    = new THREE.Scene()

      # Let's set up cameras
      # The cameras are never "moved". They only look at the scenes
      # (the trianglesplanes in particular)
      @scene[i].add @camera[i]
      @camera[i].position.z = 1
      @camera[i].lookAt(new THREE.Vector3( 0, 0, 0))

      # Attach the canvas to the container
      # DEBATE: a canvas can be passed the the renderer as an argument...!?
      @renderer[i].setSize WIDTH, HEIGHT
      container.append @renderer[i].domElement

    @prevControls = $('#prevControls')
    values        = ["XY Plane", "XY Plane", "XZ Plane", "3D View"]
    callbacks     = [@changePrevXY, @changePrevYZ, @changePrevXZ, @changePrev3D]
    buttons       = new Array(4)
    for i in [VIEW_3D, PLANE_XY, PLANE_YZ, PLANE_XZ]
      buttons[i] = document.createElement "input"
      buttons[i].setAttribute "type", "button"
      buttons[i].setAttribute "value", values[i]
      buttons[i].addEventListener "click", callbacks[i], true
      @prevControls.append buttons[i]

    # This "camera" is not a camera in the traditional sense.
    # It just takes care of the global position
    cam2d = new Flycam2d VIEWPORT_WIDTH
    @setActivePlaneXY()
    
    # FPS stats
    stats = new Stats()
    stats.getDomElement().style.position = 'absolute'
    stats.getDomElement().style.left = '0px'
    stats.getDomElement().style.top = '0px'
    $("body").append stats.getDomElement() 
    @stats = stats
    @positionStats = $("#status")

    @first = true
    # start the rendering loop
    @animate()

    # Dont forget to handle window resizing!
    $(window).resize( => @.resize() )
    
    # refresh the scene once a bucket is loaded
    # FIXME: probably not the most elgant thing to do
    # FIXME: notifies all planes when any bucket is loaded
    $(window).on("bucketloaded", => cam2d.hasChanged = true; cam2d.newBuckets = [true, true, true]) 

  animate : ->
    @renderFunction()

    window.requestAnimationFrame => @animate()

  # This is the main render function.
  # All 3D meshes and the trianglesplane are rendered here.
  renderFunction : ->

    TWEEN.update()

    # skip rendering if nothing has changed
    # This prevents you the GPU/CPU from constantly
    # working and keeps your lap cool
    # ATTENTION: this limits the FPS to 30 FPS (depending on the keypress update frequence)
    if cam2d.hasChanged is false
      return

    @updateTrianglesplane()
    
    # update postion and FPS displays
    position2d = cam2d.getGlobalPos()
    texturePositionXY = cam2d.texturePosition[0]
    # without rounding the position becomes really long and blocks the canvas mouse input
    position2d = [Math.round(position2d[0]),Math.round(position2d[1]),Math.round(position2d[2])]
    texturePositionXY = [Math.round(texturePositionXY[0]),Math.round(texturePositionXY[1]),Math.round(texturePositionXY[2])]
    @positionStats.html "Flycam2d: #{position2d}<br />texturePositionXY: #{texturePositionXY}<br />ZoomStep #{cam2d.getZoomStep(cam2d.getActivePlane())}<br />activePlane: #{cam2d.getActivePlane()}" 
    @stats.update()

    cam2d.hasChanged = false
    for i in [PLANE_XY, PLANE_YZ, PLANE_XZ, VIEW_3D]
      @renderer[i].render @scene[i], @camera[i]

  # Let's apply new pixels to the trianglesplane.
  # We do so by apply a new texture to it.
  updateTrianglesplane : ->
      return unless @meshes

      #FIXME: Using the meshes directly would again simplify the code...
      gxy = @meshes[0][PLANE_XY]
      gyz = @meshes[0][PLANE_YZ]
      gxz = @meshes[0][PLANE_XZ]
      gpxy = @meshes[1][PLANE_XY]
      gpyz = @meshes[1][PLANE_YZ]
      gpxz = @meshes[1][PLANE_XZ]
      gbxy = @meshes[2][PLANE_XY]
      gbyz = @meshes[2][PLANE_YZ]
      gbxz = @meshes[2][PLANE_XZ]

      for plane in [gxy, gpxy, gbxy]
        plane.planeID = PLANE_XY
      for plane in [gyz, gpyz, gbyz]
        plane.planeID = PLANE_YZ
      for plane in [gxz, gpxz, gbxz]
        plane.planeID = PLANE_XZ

      # sends current position to Model for preloading data
      # NEW with direction vector
      # Model.Binary.ping cam2d.getGlobalPos(), cam2d.getDirection(), cam2d.getZoomStep(PLANE_XY)
      Model.Binary.ping cam2d.getGlobalPos(), cam2d.getZoomStep(PLANE_XY)

      # sends current position to Model for caching route
      Model.Route.put cam2d.getGlobalPos()

      globalPos = cam2d.getGlobalPos()
      # Translating ThreeJS' coordinate system to the preview's one
      globalPosVec = new THREE.Vector3(globalPos[0], Game.dataSet.upperBoundary[1]-globalPos[2], globalPos[1])
      
      if @first==true           # initialize Preview
        @changePrev VIEW_3D
        @first = false

    
      i = 0       # counts which plane is used
      for plane in [gxy, gyz, gxz, gpxy, gpxz, gpyz, gbxy, gbyz, gbxz]
        i++
        offsets = cam2d.getOffsets plane.planeID
        scalingFactor = cam2d.getTextureScalingFactor plane.planeID

        # only the main planes
        if i<=3
          if  cam2d.needsUpdate(plane.planeID)
            # TODO: Why don't those lines work? it would get rid of that huge switch statement 
            #
            #plane.props.getFkt(cam2d.getGlobalPos(), cam2d.getZoomStep(plane.props.planeID)).done (buffer) ->
            #  plane.texture.image.data.set(buffer)
            switch plane.planeID
              when PLANE_XY
                Model.Binary.getXY(cam2d.getGlobalPos(), cam2d.getZoomStep(PLANE_XY)).done (buffer) ->
                  plane.texture.image.data.set(buffer)
              when PLANE_YZ
                Model.Binary.getXY(cam2d.getGlobalPos(), cam2d.getZoomStep(PLANE_XY)).done (buffer) ->
                  plane.texture.image.data.set(buffer)
              when PLANE_XZ
                Model.Binary.getXY(cam2d.getGlobalPos(), cam2d.getZoomStep(PLANE_XY)).done (buffer) ->
                  plane.texture.image.data.set(buffer)
            cam2d.notifyNewTexture plane.planeID
        
        #only for border planes
        else if i>=7 then plane.position = new THREE.Vector3(globalPosVec.x-1, globalPosVec.y-1, globalPosVec.z-1)
        
        # only preview planes
        else
          switch plane.planeID
            when PLANE_XY then plane.texture = gxy.texture.clone()
            when PLANE_YZ then plane.texture = gyz.texture.clone()
            when PLANE_XZ then plane.texture = gxz.texture.clone()
          plane.position = globalPosVec

        # only for preview and border planes
        if i>=4
          sFactor = cam2d.getPlaneScalingFactor plane.planeID
          plane.scale.x = plane.scale.y = plane.scale.z = sFactor
        
        # only for main and preview planes
        if i<=6
          plane.texture.needsUpdate = true
          plane.material.map = plane.texture

        # only main planes
        if i<=3
          plane.material.map.repeat.x = VIEWPORT_WIDTH*scalingFactor / 508;
          plane.material.map.repeat.y = VIEWPORT_WIDTH*scalingFactor / 508;
          plane.material.map.offset.x = offsets[0] / 508;
          plane.material.map.offset.y = offsets[1] / 508;
  
  # Adds a new Three.js geometry to the scene.
  # This provides the public interface to the GeometryFactory.
  addGeometry : (planeID, geometry) ->
    @scene[planeID].add geometry

  changePrev : (id) ->
    # In order for the rotation to be correct, it is not sufficient
    # to just use THREEJS' lookAt() function, because it may still
    # look at the plane in a wrong angle. Therefore, the rotation
    # has to be hard coded.
    @tween = new TWEEN.Tween({ texts: @texts, camera: @camera[VIEW_3D], x: @camera[VIEW_3D].position.x, y: @camera[VIEW_3D].position.y, z: @camera[VIEW_3D].position.z, xRot: @camera[VIEW_3D].rotation.x, yRot: @camera[VIEW_3D].rotation.y, zRot: @camera[VIEW_3D].rotation.z, l: @camera[VIEW_3D].left, r: @camera[VIEW_3D].right, t: @camera[VIEW_3D].top, b: @camera[VIEW_3D].bottom})
    b = Game.dataSet.upperBoundary
    switch id
      when VIEW_3D
        scale = Math.sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2])/1.9
        @tween.to({  x: 4000, y: 4000, z: 5000, xRot: @degToRad(-36.25), yRot: @degToRad(30.6), zRot: @degToRad(20.47), l: -scale, r: scale, t: scale+scale*0.25, b: -scale+scale*0.25}, 800)
        .onUpdate(@updateCameraPrev)
        .start()
        #rotation: (-36.25, 30.6, 20.47) -> (-36.25, 30.6, 20.47)
      when PLANE_XY
        scale = (Math.max b[0], b[1])/1.75
        @tween.to({  x: b[0]/2, y: 4000, z: b[1]/2, xRot: @degToRad(-90), yRot: @degToRad(0), zRot: @degToRad(0), l: -scale, r: scale, t: scale+scale*0.12, b: -scale+scale*0.12}, 800)
        .onUpdate(@updateCameraPrev)
        .start()
        #rotation: (-90, 0, 90) -> (-90, 0, 0)
      when PLANE_YZ
        scale = (Math.max b[1], b[2])/1.75
        @tween.to({  x: 4000, y: b[2]/2, z: b[1]/2, xRot: @degToRad(-90), yRot: @degToRad(90), zRot: @degToRad(0), l: -scale, r: scale, t: scale+scale*0.12, b: -scale+scale*0.12}, 800)
        .onUpdate(@updateCameraPrev)
        .start()
        #rotation: (0, 90, 0) -> (-90, 90, 0)
      when PLANE_XZ
        scale = (Math.max b[0], b[2])/1.75
        @tween.to({  x: b[0]/2, y: b[2]/2, z: 4000, xRot: @degToRad(0), yRot: @degToRad(0), zRot: @degToRad(0), l: -scale, r: scale, t: scale+scale*0.12, b: -scale+scale*0.12}, 800)
        .onUpdate(@updateCameraPrev)
        .start()
        #rotation: (0, 0, 0) -> (0, 0, 0)
    cam2d.hasChanged = true

  degToRad : (deg) -> deg/180*Math.PI

  updateCameraPrev : ->
    @camera.position = new THREE.Vector3(@x, @y, @z)
    @camera.rotation = new THREE.Vector3(@xRot, @yRot, @zRot)
    @camera.left = @l
    @camera.right = @r
    @camera.top = @t
    @camera.bottom = @b
    if @texts
      for text in @texts
        text.rotation = new THREE.Vector3(@xRot, @yRot, @zRot)
    @camera.updateProjectionMatrix()
    cam2d.hasChanged = true

  changePrev3D : => View.changePrev(VIEW_3D)
  changePrevXY : => View.changePrev(PLANE_XY)
  changePrevYZ : => View.changePrev(PLANE_YZ)
  changePrevXZ : => View.changePrev(PLANE_XZ)

  #Apply a single draw (not used right now)
  draw : ->
    #FIXME: this is dirty
    cam2d.hasChanged = true

  #Call this after the canvas was resized to fix the viewport
  resize : ->
    #FIXME: Is really the window's width or rather the DIV's?
    container = $("#render")
    WIDTH = (container.width()-49)/2
    HEIGHT = (container.height()-49)/2

    for i in [PLANE_XY, PLANE_YZ, PLANE_XZ, VIEW_3D]
      @renderer[i].setSize( WIDTH, HEIGHT)
      @camera[i].aspect = WIDTH / HEIGHT
      @camera[i].updateProjectionMatrix()
    @draw()

############################################################################
#Interface for Controller
  # TODO: Some of those are probably obsolete

  setGlobalPos : (pos) ->
    cam2d.setGlobalPos(pos)

  getGlobalPos : ->
    cam2d.getGlobalPos()

  setDirection : (direction) ->
    cam2d.setDirection direction

  # Should not be used anymore
  #move : (p) ->
  #  cam2d.move p

  moveActivePlane : (p) ->
    cam2d.moveActivePlane p
    @updateRoutePosition p

  #FIXME: why can't I call move() from within this function?
  moveX : (x) -> 
    cam2d.moveActivePlane [x, 0, 0]
    View.updateRoutePosition [x, 0, 0]

  moveY : (y) ->
    cam2d.moveActivePlane [0, y, 0]
    View.updateRoutePosition [0, y, 0]
  
  moveZ : (z) ->
    cam2d.moveActivePlane [0, 0, z]
    View.updateRoutePosition [0, 0, z]

  prevViewportSize : =>
    (View.camera[VIEW_3D].right - View.camera[VIEW_3D].left)         # always quadratic

  zoomPrev : (value) =>
    factor = Math.pow(0.9, value)
    middleX = (View.camera[VIEW_3D].left + View.camera[VIEW_3D].right)/2
    middleY = (View.camera[VIEW_3D].bottom + View.camera[VIEW_3D].top)/2
    size = View.prevViewportSize()
    View.camera[VIEW_3D].left = middleX - factor*size/2
    View.camera[VIEW_3D].right = middleX + factor*size/2
    View.camera[VIEW_3D].top = middleY + factor*size/2
    View.camera[VIEW_3D].bottom = middleY - factor*size/2
    View.camera[VIEW_3D].updateProjectionMatrix()
    cam2d.hasChanged = true

  movePrevX : (x) =>
    size = View.prevViewportSize()
    View.camera[VIEW_3D].left += x*size/384
    View.camera[VIEW_3D].right += x*size/384
    View.camera[VIEW_3D].updateProjectionMatrix()
    cam2d.hasChanged = true

  movePrevY : (y) =>
    size = View.prevViewportSize()
    View.camera[VIEW_3D].top -= y*size/384
    View.camera[VIEW_3D].bottom -= y*size/384
    View.camera[VIEW_3D].updateProjectionMatrix()
    cam2d.hasChanged = true
  
  scaleTrianglesPlane : (delta) ->
    @x = 1 unless @x
    if (@x+delta > 0.75) and (@x+delta < 1.5)
      @x += Number(delta)
      WIDTH = HEIGHT = @x * 384
      container = $("#render")
      container.width(2 * WIDTH + 48)
      container.height(2 * HEIGHT + 48)

      # scales the 3D-view controls
      prevControl = $("#prevControls")
      prevControl.css({top: @x * 440 + "px", left: @x * 420 + "px"})

      @resize()

  zoomIn : ->
    cam2d.zoomIn(cam2d.getActivePlane())

  #todo: validation in Model
  zoomOut : ->
    cam2d.zoomOut(cam2d.getActivePlane())

  setActivePlaneXY : ->
    View.setActivePlane PLANE_XY

  setActivePlaneYZ : ->
    View.setActivePlane PLANE_YZ

  setActivePlaneXZ : ->
    View.setActivePlane PLANE_XZ

  setActivePlane : (planeID) ->
    cam2d.setActivePlane planeID
    for i in [0..2]
      $("canvas")[i].style.borderColor = if i==planeID then "#888800" else "#C7D1D8"
    cam2d.hasChanged = true

  setWaypoint : (position) ->
    curGlobalPos = cam2d.getGlobalPos()
    curZoomStep = cam2d.getZoomStep(cam2d.getActivePlane()) + 1
    # calculate the global position of the rightclick
    position = [curGlobalPos[0] - 192/curZoomStep + position[0]/curZoomStep, curGlobalPos[1] - 192/curZoomStep + position[1]/curZoomStep, curGlobalPos[2]]
    unless @curIndex
      @curIndex = 0
    # Translating ThreeJS' coordinate system to the preview's one
    if @curIndex < @maxRouteLen
      @route.geometry.vertices[@curIndex] = new THREE.Vector3(position[0], Game.dataSet.upperBoundary[1] - position[2], position[1])
      @routeView.geometry.vertices[@curIndex] = new THREE.Vector3(position[0], -position[1], -position[2])

      particle = new THREE.Mesh(new THREE.CubeGeometry(30, 30, 30, 10, 10, 10), new THREE.MeshBasicMaterial({color: 0xff0000}))
      particle.position.x = position[0]
      particle.position.y = Game.dataSet.upperBoundary[1] - position[2]
      particle.position.z = position[1]
      @particles.push particle
      @addGeometry VIEW_3D, particle

      @route.geometry.verticesNeedUpdate = true
      @routeView.geometry.verticesNeedUpdate = true
      @curIndex += 1
      cam2d.hasChanged = true

  onPreviewClick : (position) ->
    # vector with direction from camera position to click position
    vector = new THREE.Vector3((position[0] / 384 ) * 2 - 1, - (position[1] / 384) * 2 + 1, 0.5)
    
    # create a ray with the direction of this vector
    projector = new THREE.Projector()
    ray = projector.pickingRay(vector, @camera[VIEW_3D])

    # identify clicked object
    intersects = ray.intersectObjects(@particles)

    if (intersects.length > 0)
      intersects[0].object.material.color.setHex(Math.random() * 0xffffff)
      objPos = intersects[0].object.position
      # jump to the nodes position
      cam2d.setGlobalPos [objPos.x, objPos.z, -objPos.y + Game.dataSet.upperBoundary[1]]

  createRoute : (maxRouteLen) ->
    # create route to show in previewBox and pre-allocate buffer
    @maxRouteLen = maxRouteLen
    routeGeometry = new THREE.Geometry()
    routeGeometryView = new THREE.Geometry()
    i = 0
    while i < maxRouteLen
      # workaround to hide the unused vertices
      routeGeometry.vertices.push(new THREE.Vector2(0, 0))
      routeGeometryView.vertices.push(new THREE.Vector2(0, 0))
      i += 1

    routeGeometry.dynamic = true
    routeGeometryView.dynamic = true
    route = new THREE.Line(routeGeometry, new THREE.LineBasicMaterial({color: 0xff0000, linewidth: 1}))
    routeView = new THREE.Line(routeGeometryView, new THREE.LineBasicMaterial({color: 0xff0000, linewidth: 1}))
    @route = route
    @routeView = routeView
    @routeView.position = new THREE.Vector3(-2800, 2800, 5601)

    @particles = []

    @addGeometry VIEW_3D, route
    @addGeometry PLANE_XY, routeView

  updateRoutePosition : (p) ->
    @routeView.position = new THREE.Vector3(@routeView.position.x - p[0], @routeView.position.y + p[1], cam2d.getGlobalPos()[2]+1)
    @routeView.geometry.verticesNeedUpdate = true
