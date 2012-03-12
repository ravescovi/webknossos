# This takes care of the route. 
define ["libs/request"], (request) ->
		
	Route = 
		# Constants
		BUFFER_SIZE : 262144 # 1024 * 1204 / 4
		
		# Variables
		branchStack : []

		# Initializes this module and returns a matrix to start your work.
		initialize : ->

			unless @initializeDeferred
				
				@initializeDeferred = $.Deferred()

				@initializeDeferred.fail =>
					@initializeDeferred = null

				request(url : '/route/initialize').then( 
					
					(data) =>
						try
							data = JSON.parse data
							@id  = data.id
							@createBuffer()
						catch ex
							@initializeDeferred.reject(ex)
						@initializeDeferred.resolve(data.matrix)

					(err) =>
						@initializeDeferred.reject(err)

				)
			
			@initializeDeferred.promise()

		# Pushes the buffered route to the server. Pushing happens at most 
		# every 30 seconds.
		push : ->
			@push = _.throttle2(_.mutexDeferred(@pushImpl, -1), 30000)
			@push()

		pushImpl : ->

			deferred = $.Deferred()
			
			@initialize().done =>
				
				transportBuffer = new Float32Array(@buffer.subarray(0, @index))
				@createBuffer()

				request(
					url    : "/route/#{@id}"
					method : 'POST'
					data   : transportBuffer.buffer
				).fail( =>
					
					oldBuffer = @buffer
					oldIndex  = @index
					@createBuffer()
					@buffer.set(oldBuffer.subarray(0, oldIndex))
					@buffer.set(transportBuffer, oldIndex)
					@index = oldIndex + transportBuffer.length

					@push()

				).always(-> deferred.resolve())
			
			deferred.promise()

		createBuffer : ->
			@index = 0
			@buffer = new Float32Array(@BUFFER_SIZE)

		addToBuffer : (typeNumber, value) ->

			@buffer[@index++] = typeNumber
			
			if value
				switch typeNumber
					when 0
						@buffer.set(value.subarray(0, 3), @index)
						@index += 3
					when 1
						@buffer.set(value.subarray(0, 16), @index)
						@index += 16

			@push()

		putBranch : (matrix) ->

			@initialize().done =>
				
				@addToBuffer(1, matrix)
				@branchStack.push(matrix)

		popBranch : ->

			deferred = $.Deferred()
			@initialize()
				.done =>

					branchStack = @branchStack
					
					if branchStack.length > 0
						@addToBuffer(2)
						deferred.resolve(branchStack.pop())
					else
						deferred.reject()

				.fail ->
					deferred.reject()

			deferred.promise()

		# Add a point to the buffer. Just keep adding them.
		put : (position) ->
			
			@initialize().done =>
				
				position = V3.round(position)
				lastPosition = @lastPosition

				if not lastPosition or 
				lastPosition[0] != position[0] or 
				lastPosition[1] != position[1] or 
				lastPosition[2] != position[2]
					@lastPosition = position
					@addToBuffer(0, position)